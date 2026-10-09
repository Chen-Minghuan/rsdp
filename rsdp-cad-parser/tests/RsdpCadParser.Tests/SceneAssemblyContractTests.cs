using System.Security.Cryptography;
using System.Text.Json;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using RsdpCadParser.SceneAssembly;
using Xunit;
using Xunit.Abstractions;

namespace RsdpCadParser.Tests;

/// <summary>
/// Stage ⑦ Scene Assembly 契约测试（目标架构 §3.7，P1.5）：
/// parsedScene 全返回路径在场、纯函数确定性（同输入同输出）、configHash 配置漂移可察觉、
/// 装配层只搬运既有产出（rooms/walls/dimensions/relations 与顶层结果一致）、金样本零差异。
/// </summary>
public class SceneAssemblyContractTests
{
    private readonly ITestOutputHelper _output;
    public SceneAssemblyContractTests(ITestOutputHelper output) => _output = output;

    private static string FixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", name);

    private static CadParseResult ParseFixture(string name, ParserOptions? opt = null)
        => CadParsePipeline.Parse(File.ReadAllBytes(FixturePath(name)), name, opt);

    // ① parsedScene 在成功与失败路径都在场（FAILED 也带 metadata，供诊断对账）
    [Fact]
    public void ParsedScene_Present_OnSuccessAndFailure()
    {
        var ok = ParseFixture("two_inserts.dxf");
        Assert.True(ok.Success);
        var scene = Assert.IsType<ParsedCadScene>(ok.ParsedScene);
        Assert.False(string.IsNullOrEmpty(scene.Metadata.InputHash));
        Assert.Equal(ParserInfo.ParserVersion, scene.Metadata.ParserVersion);
        Assert.Equal(ParserInfo.SceneVersion, scene.Metadata.SceneVersion);
        Assert.False(string.IsNullOrEmpty(scene.Metadata.ConfigHash));
        // 装配视图与顶层结果一致（同一事实的两个投影）
        Assert.Equal(ok.Furniture!.Count, scene.Furniture.Count);
        Assert.Equal(ok.Rooms.Count + ok.UnnamedRegions.Count, scene.Rooms.Count);
        Assert.Equal(ok.QualityIssues.Count, scene.QualityIssues.Count);
        Assert.Equal(ok.Capabilities, scene.Capabilities);
        Assert.NotNull(scene.UnitResolution);
        Assert.NotNull(scene.TargetScene);
        // 未实现域恒为空数组（不虚构对象）
        Assert.Empty(scene.Doors);
        Assert.Empty(scene.Windows);
        Assert.Empty(scene.DimensionChains);

        var corrupt = CadParsePipeline.Parse(new byte[] { 1, 2, 3, 4, 5 }, "bad.dwg");
        Assert.False(corrupt.Success);
        Assert.Equal("FAILED", corrupt.ParseStatus);
        var failedScene = Assert.IsType<ParsedCadScene>(corrupt.ParsedScene);
        Assert.False(string.IsNullOrEmpty(failedScene.Metadata.InputHash));
        Assert.Empty(failedScene.Rooms);
    }

    // ② 纯函数确定性：同输入重复解析，parsedScene 完全一致（可回归）
    [Fact]
    public void ParsedScene_Deterministic_SameInputSameOutput()
    {
        var bytes = File.ReadAllBytes(FixturePath("two_inserts.dxf"));
        var r1 = CadParsePipeline.Parse(bytes, "two_inserts.dxf");
        var r2 = CadParsePipeline.Parse(bytes, "two_inserts.dxf");
        string Json(ParsedCadScene s) => JsonSerializer.Serialize(s);
        Assert.Equal(Json(r1.ParsedScene!), Json(r2.ParsedScene!));
        // inputHash = 文件字节 sha256（小写 hex 全量）
        var expected = Convert.ToHexString(SHA256.HashData(bytes)).ToLowerInvariant();
        Assert.Equal(expected, r1.ParsedScene!.Metadata.InputHash);
    }

    // ③ configHash 随配置漂移变化（同配置同指纹）
    [Fact]
    public void ConfigHash_ChangesWithOptions()
    {
        var a = ParseFixture("two_inserts.dxf");
        var b = ParseFixture("two_inserts.dxf", new ParserOptions { MaxExpandedEntities = 1 });
        Assert.NotEqual(a.ParsedScene!.Metadata.ConfigHash, b.ParsedScene!.Metadata.ConfigHash);
        var c = ParseFixture("two_inserts.dxf");
        Assert.Equal(a.ParsedScene!.Metadata.ConfigHash, c.ParsedScene!.Metadata.ConfigHash);
    }

    // ④ 装配层单元测试：walls/dimensions 只搬运已有产出，ID 稳定、不虚构字段
    [Fact]
    public void Assembler_MapsWallsAndDimensions_AsRawFacts()
    {
        var gf = NtsGeometryServices.Instance.CreateGeometryFactory();
        var solid = gf.CreatePolygon(new[]
        {
            new Coordinate(0, 0), new Coordinate(200, 0),
            new Coordinate(200, 4000), new Coordinate(0, 4000), new Coordinate(0, 0)
        });
        var walls = new WallExtractor.WallExtractionResult();
        walls.WallSolids.Add(solid);
        walls.WallLines.Add(new List<Coordinate> { new(0, 0), new(3000, 0) });

        var dims = new List<FlatEntity>
        {
            new()
            {
                Kind = FlatKind.Dimension, Layer = "DIM", Measure = 2600.0,
                TextPos = new Coordinate(1500, 200), SourceHandle = "A1F",
                InsertPath = new List<string> { "B2" }, BlockDefinitionName = "*D1"
            }
        };

        var result = new CadParseResult { Success = true };
        var scene = CadSceneAssembler.Assemble(result, walls, dims, "hash", new ParserOptions());

        var wSolid = Assert.Single(scene.Walls, w => w.Kind == "SOLID");
        Assert.StartsWith("W-", wSolid.WallId);
        Assert.Equal("WALL_SOLID", wSolid.Source);
        Assert.Equal(0.8, wSolid.AreaM2); // 0.2m × 4m
        Assert.NotNull(wSolid.Polygon);
        var wLine = Assert.Single(scene.Walls, w => w.Kind == "LINE");
        Assert.Equal("WALL_LAYER_LINE", wLine.Source);
        Assert.Equal(3000, wLine.LengthMm);

        var d = Assert.Single(scene.Dimensions);
        Assert.StartsWith("DIM-", d.DimensionId);
        Assert.Equal(2600, d.MeasurementMm);
        Assert.Equal("DIM", d.Layer);
        Assert.Equal("A1F", d.SourceHandle);
        Assert.Equal("UNKNOWN", d.Role); // role 分类属 P2-A，恒 UNKNOWN
        Assert.Equal(1500, d.TextPosition!.X);

        // ID 内容寻址：同内容重复装配 → 同 ID
        var scene2 = CadSceneAssembler.Assemble(result, walls, dims, "hash", new ParserOptions());
        Assert.Equal(scene.Walls.Select(w => w.WallId), scene2.Walls.Select(w => w.WallId));
        Assert.Equal(scene.Dimensions[0].DimensionId, scene2.Dimensions[0].DimensionId);
    }

    // ⑤ 金样本：rooms = 命名 + 未命名并集、walls 非空、relations 与家具归属一致、顶层字段零差异
    [Fact]
    public void GoldenSample_ParsedScene_ConsistentWithTopLevel()
    {
        var path = GoldenFile.Locate("家具平面布局图.dwg");
        if (path == null)
        {
            _output.WriteLine("SKIP: 金样本文件不存在");
            return;
        }
        var result = CadParsePipeline.Parse(File.ReadAllBytes(path), Path.GetFileName(path));
        Assert.True(result.Success);
        var scene = Assert.IsType<ParsedCadScene>(result.ParsedScene);

        // rooms 装配 = 命名房间 + 未命名空间（同一 roomId 序列，无丢失无新增）
        Assert.Equal(result.Rooms.Count + result.UnnamedRegions.Count, scene.Rooms.Count);
        Assert.Equal(
            result.Rooms.Select(r => r.RoomId).Concat(result.UnnamedRegions.Select(u => u.RoomId)).OrderBy(x => x),
            scene.Rooms.Select(r => r.RoomId).OrderBy(x => x));
        Assert.Equal(result.Rooms.Count, scene.Rooms.Count(r => r.Named));
        Assert.All(scene.Rooms.Where(r => !r.Named), r => Assert.Null(r.Confidence)); // 未命名无置信度来源，不虚构

        // 墙体：金样本有 HATCH 实心墙身（ANSI31），必须装配出 SOLID 墙
        Assert.NotEmpty(scene.Walls);
        Assert.Contains(scene.Walls, w => w.Kind == "SOLID");
        Assert.All(scene.Walls, w => Assert.StartsWith("W-", w.WallId));

        // 关系 = 有 roomId 的家具集合，按 fromId 排序
        var expected = result.Furniture!.Where(f => f.RoomId != null).OrderBy(f => f.FurnitureId).ToList();
        Assert.Equal(expected.Count, scene.Relations.Count);
        Assert.All(scene.Relations, rel =>
        {
            Assert.Equal("FURNITURE_IN_ROOM", rel.Type);
            Assert.Equal("ANCHOR_PIP_OVERLAP", rel.Source);
            Assert.Contains(result.Furniture!, f => f.FurnitureId == rel.FromId && f.RoomId == rel.ToId);
        });

        // 金样本顶层可观察输出零差异抽查（装配层不得影响既有产出）
        Assert.True(result.Rooms.Count >= 9);
        Assert.Equal(14, result.Furniture!.Count);
        _output.WriteLine($"parsedScene: rooms={scene.Rooms.Count} walls={scene.Walls.Count} " +
                          $"dims={scene.Dimensions.Count} relations={scene.Relations.Count}");
    }
}
