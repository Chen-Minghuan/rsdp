using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser.SceneAssembly;

/// <summary>
/// Stage ⑦ Scene Assembly（目标架构 §3.7，Strangler Migration 第一步）：
/// 把现有各 Extractor 的产出统一装配为 ParsedCadScene，挂到 /parse 响应 parsedScene 字段。
/// 只做收集 / 映射 / 组装 / contract normalization——不重写 Extractor，不改变既有顶层字段语义。
/// 输入均为解析过程的已有中间产物；装配是纯函数（同输入 + 同配置 → 同输出，可回归）。
/// </summary>
public static class CadSceneAssembler
{
    /// <summary>
    /// 装配 ParsedCadScene。
    /// </summary>
    /// <param name="result">已完成的顶层解析结果（rooms/furniture/qualityIssues/capabilities 等已填充）。</param>
    /// <param name="walls">空间分析路径的墙体提取产出；早期失败路径为 null。</param>
    /// <param name="dimensions">目标场景内 DIMENSION 扁平实体；早期失败路径为 null。</param>
    /// <param name="inputHash">输入文件字节 sha256（小写 hex）。</param>
    /// <param name="opt">本次解析生效的 ParserOptions（configHash 来源）。</param>
    public static ParsedCadScene Assemble(
        CadParseResult result,
        WallExtractor.WallExtractionResult? walls,
        List<FlatEntity>? dimensions,
        string inputHash,
        ParserOptions opt)
    {
        var scene = new ParsedCadScene
        {
            Metadata = new SceneMetadata
            {
                InputHash = inputHash,
                ParserVersion = ParserInfo.ParserVersion,
                SceneVersion = ParserInfo.SceneVersion,
                ConfigHash = ConfigHash(opt)
            },
            UnitResolution = result.UnitResolution,
            TargetScene = result.TargetScene,
            TargetSceneCandidates = result.TargetSceneCandidates ?? new List<TargetSceneCandidateDto>(),
            Furniture = result.Furniture ?? new List<FurnitureDto>(),
            QualityIssues = result.QualityIssues,
            Capabilities = result.Capabilities ?? new Dictionary<string, string>()
        };

        // 空间：命名房间 + 未命名空间统一入 rooms[]（named 区分；未命名置信度无来源，不虚构）
        foreach (var r in result.Rooms)
        {
            scene.Rooms.Add(new SceneRoom
            {
                RoomId = r.RoomId,
                Named = true,
                Label = r.Label,
                RoomType = r.RoomType,
                MemberLabels = r.MemberLabels,
                SpaceGroup = r.SpaceGroup,
                Polygon = r.Polygon,
                BBox = r.BBox,
                WidthMm = r.WidthMm,
                DepthMm = r.DepthMm,
                AreaM2 = r.AreaM2,
                LabelPoint = r.LabelPoint,
                DimensionSource = r.DimensionSource,
                DimensionCheck = r.DimensionCheck,
                Confidence = r.Confidence
            });
        }
        foreach (var u in result.UnnamedRegions)
        {
            scene.Rooms.Add(new SceneRoom
            {
                RoomId = u.RoomId,
                Named = false,
                Polygon = u.Polygon,
                BBox = u.BBox,
                WidthMm = u.WidthMm,
                DepthMm = u.DepthMm,
                AreaM2 = u.AreaM2,
                LabelPoint = u.LabelPoint,
                Hint = u.Hint
            });
        }

        // 墙体：实心墙身（HATCH/WIPEOUT/SOLID 等价物）+ 墙图层线，只装配不判定
        if (walls != null)
        {
            foreach (var solid in walls.WallSolids)
            {
                var ring = solid.ExteriorRing.Coordinates;
                scene.Walls.Add(new SceneWall
                {
                    WallId = WallId("SOLID", ring),
                    Kind = "SOLID",
                    Source = "WALL_SOLID",
                    Polygon = ring.Select(ToPointArray).ToArray(),
                    AreaM2 = Math.Round(solid.Area / 1e6, 4),
                    BBox = ToBounds(solid.EnvelopeInternal)
                });
            }
            foreach (var line in walls.WallLines)
            {
                double length = 0;
                for (int i = 1; i < line.Count; i++) length += line[i - 1].Distance(line[i]);
                scene.Walls.Add(new SceneWall
                {
                    WallId = WallId("LINE", line),
                    Kind = "LINE",
                    Source = "WALL_LAYER_LINE",
                    Points = line.Select(ToPointArray).ToArray(),
                    LengthMm = Math.Round(length, 1),
                    BBox = ToBounds(BBoxOf(line))
                });
            }
        }

        // Raw Dimension：只读事实搬运（role 分类属 P2-A，恒 UNKNOWN）
        if (dimensions != null)
        {
            foreach (var d in dimensions)
            {
                scene.Dimensions.Add(new SceneDimension
                {
                    DimensionId = DimensionId(d),
                    MeasurementMm = d.Measure.HasValue ? Math.Round(d.Measure.Value, 1) : null,
                    TextPosition = d.TextPos != null
                        ? new PointDto { X = Math.Round(d.TextPos.X, 1), Y = Math.Round(d.TextPos.Y, 1) }
                        : null,
                    Layer = d.Layer,
                    SourceHandle = d.SourceHandle,
                    InsertPath = d.InsertPath,
                    BlockDefinitionName = d.BlockDefinitionName
                });
            }
        }

        // 关系：家具→空间归属（锚点 PIP + 轮廓重叠复核的产出物化），确定性排序
        foreach (var f in scene.Furniture.Where(f => f.RoomId != null).OrderBy(f => f.FurnitureId))
        {
            scene.Relations.Add(new SceneRelation
            {
                Type = "FURNITURE_IN_ROOM",
                FromId = f.FurnitureId,
                ToId = f.RoomId!,
                Source = "ANCHOR_PIP_OVERLAP",
                Confidence = f.DetectionConfidence
            });
        }

        return scene;
    }

    /// <summary>配置指纹：ParserOptions 序列化 sha256 前 8 位（同二进制内反射顺序确定）。</summary>
    private static string ConfigHash(ParserOptions opt)
        => ParserInfo.ShortHash(JsonSerializer.Serialize(opt));

    /// <summary>wallId = "W-" + sha256(kind|顶点按 0.1mm 量化) 前 8 位 hex。</summary>
    private static string WallId(string kind, IReadOnlyList<Coordinate> pts)
        => "W-" + ParserInfo.ShortHash(kind + "|" + string.Join(";", pts.Select(p => $"{Math.Round(p.X, 1)},{Math.Round(p.Y, 1)}")));

    /// <summary>dimensionId = "DIM-" + sha256（来源句柄优先，否则 图层|测量值|位置）前 8 位 hex。</summary>
    private static string DimensionId(FlatEntity d)
        => "DIM-" + ParserInfo.ShortHash(d.SourceHandle
            ?? $"{d.Layer}|{d.Measure}|{(d.TextPos != null ? $"{Math.Round(d.TextPos.X, 1)},{Math.Round(d.TextPos.Y, 1)}" : "")}");

    private static double[] ToPointArray(Coordinate c) => new[] { Math.Round(c.X, 1), Math.Round(c.Y, 1) };

    private static Envelope BBoxOf(IEnumerable<Coordinate> pts)
    {
        var e = new Envelope();
        foreach (var p in pts) e.ExpandToInclude(p);
        return e;
    }

    private static BoundsDto ToBounds(Envelope e) => new()
    {
        MinX = Math.Round(e.MinX, 1),
        MinY = Math.Round(e.MinY, 1),
        MaxX = Math.Round(e.MaxX, 1),
        MaxY = Math.Round(e.MaxY, 1)
    };
}
