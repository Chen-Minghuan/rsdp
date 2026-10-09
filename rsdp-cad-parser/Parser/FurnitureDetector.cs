using System.Text;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// INSERT 家具候选识别（设计文档 §10.1、§18.5）：
/// - 识别对象以家具语义对应的 INSERT 实例为单位；同一块定义插入 N 次输出 N 个实例（furnitureId 由完整 insertPath 哈希，天然不同）；
/// - 父块本身是一件家具时（名称/图层有家具语义），内部坐垫、五金等子块不再单独计数；
/// - 顶层块是通用容器（无语义且含可分类子块）时下钻到内部家具节点；
/// - 非家具块（门/窗/洁具/灯具/标注/设备等）整块排除；
/// - 证据不足输出 UNKNOWN，绝不丢弃、绝不强行归类。
/// </summary>
public static class FurnitureDetector
{
    /// <summary>Detect 的详细返回：家具候选 + 视觉语义分组的代表图元（块局部坐标，供 isolation 渲染）。</summary>
    public class DetectResult
    {
        public List<FurnitureDto> Items { get; set; } = new();
        /// <summary>有效非重复家具的原始世界坐标图元，供规范底图恢复真实线稿。</summary>
        public List<FlatEntity> PreviewPrimitives { get; set; } = new();
        /// <summary>visionGroupId → 代表实例的展开图元（块局部坐标系，旋转已归一化）。仅 VALID 候选。</summary>
        public Dictionary<string, List<FlatEntity>> VisionGroupLocalPrims { get; set; } = new();
    }

    public static List<FurnitureDto> Detect(
        IReadOnlyList<BlockResolver.InsertNode> roots,
        IReadOnlyList<(string RoomId, Polygon Geom)> rooms,
        Envelope targetRegion,
        List<string>? diagnostics = null,
        ParserOptions? opt = null)
        => DetectDetailed(roots, rooms, targetRegion, diagnostics, opt).Items;

    /// <summary>
    /// 家具候选识别 + P1-A 候选清洗 + P1-B 去重分组 + P1-C 视觉语义分组。
    /// 清洗/去重均只标记不删除：REJECTED 供复核恢复，DUPLICATE 供 Scene 修正后找回。
    /// </summary>
    public static DetectResult DetectDetailed(
        IReadOnlyList<BlockResolver.InsertNode> roots,
        IReadOnlyList<(string RoomId, Polygon Geom)> rooms,
        Envelope targetRegion,
        List<string>? diagnostics = null,
        ParserOptions? opt = null)
    {
        var result = new List<FurnitureDto>();
        var nodeById = new Dictionary<string, BlockResolver.InsertNode>();
        int excluded = 0, sceneContainers = 0;
        foreach (var root in roots)
            CollectNode(root, rooms, targetRegion, result, ref excluded, ref sceneContainers, opt, nodeById);
        if (excluded > 0) diagnostics?.Add($"非家具块排除 {excluded} 个 INSERT 实例（门/窗/洁具/灯具/标注/设备等）");
        if (sceneContainers > 0)
            diagnostics?.Add($"方案级容器块剔除 {sceneContainers} 个 INSERT 实例（跨度超 {opt?.MaxFurnitureSpanMm / 1000:F0}m，下钻子块找真家具，不作为单件家具）");

        var o = opt ?? new ParserOptions();
        int rejected = ApplyCandidateHygiene(result, o);
        if (rejected > 0)
            diagnostics?.Add($"候选清洗（P1-A）：{rejected} 件尺寸/几何无效标记 REJECTED（不删除，供复核恢复）");
        var visionPrims = AssignVisionGroups(result, nodeById, o, diagnostics);
        diagnostics?.Add($"家具候选 {result.Count} 个（UNKNOWN {result.Count(f => f.Category == "UNKNOWN")} 个）");
        var previewPrims = result
            .Where(f => f.CandidateStatus == "VALID" && f.DuplicateStatus != "DUPLICATE")
            .Where(f => nodeById.ContainsKey(f.FurnitureId))
            .SelectMany(f => nodeById[f.FurnitureId].AllPrimitives())
            .Where(e => e.Kind is FlatKind.Line or FlatKind.Polyline or FlatKind.Hatch)
            .Distinct().ToList();
        return new DetectResult { Items = result, VisionGroupLocalPrims = visionPrims,
            PreviewPrimitives = previewPrims };
    }

    /// <summary>递归收集：有家具语义 → 输出本节点；无语义容器 → 下钻子节点。</summary>
    private static void CollectNode(
        BlockResolver.InsertNode node,
        IReadOnlyList<(string RoomId, Polygon Geom)> rooms,
        Envelope targetRegion,
        List<FurnitureDto> output,
        ref int excluded,
        ref int sceneContainers,
        ParserOptions? opt,
        Dictionary<string, BlockResolver.InsertNode> nodeById)
    {
        // P1 块角色守卫：方案级容器/超大跨度块不是家具——下钻子块找真家具，自身绝不输出
        if (opt != null)
        {
            // 方案级容器（大跨度+多图元）或绝对超大（>4×家具上限≈32m，无论图元多少）都不是家具
            var role = BlockRoleClassifier.Classify(node, opt);
            if (role.Role == BlockRole.SCENE_CONTAINER || role.MaxSpanMm > opt.MaxFurnitureSpanMm * 4)
            {
                sceneContainers++;
                foreach (var child in node.Children)
                    CollectNode(child, rooms, targetRegion, output, ref excluded, ref sceneContainers, opt, nodeById);
                return;
            }
        }

        var verdict = FurnitureClassifier.Classify(node.BlockName, node.Attributes, node.Layer);
        if (verdict.Excluded)
        {
            excluded++;
            return;
        }

        bool hasClassifiableChild = node.Children.Any(c =>
        {
            var cv = FurnitureClassifier.Classify(c.BlockName, c.Attributes, c.Layer);
            return !cv.Excluded && cv.Category is not ("UNKNOWN");
        });

        // 弱语义容器（自身 UNKNOWN，或仅靠图层证据的 OTHER）且含可分类子块 → 下钻；
        // 自身 UNKNOWN/OTHER 且子块也全无法分类 → 整体保留为一个候选（不拆碎）
        if (verdict.Category is "UNKNOWN" or "OTHER" && hasClassifiableChild)
        {
            foreach (var child in node.Children)
                CollectNode(child, rooms, targetRegion, output, ref excluded, ref sceneContainers, opt, nodeById);
            return;
        }

        var dto = BuildFurniture(node, verdict, rooms, targetRegion, opt);
        nodeById[dto.FurnitureId] = node;
        output.Add(dto);
    }

    private static FurnitureDto BuildFurniture(
        BlockResolver.InsertNode node,
        FurnitureClassifier.Verdict verdict,
        IReadOnlyList<(string RoomId, Polygon Geom)> rooms,
        Envelope targetRegion,
        ParserOptions? opt)
    {
        var flags = new List<string>(node.QualityFlags);
        var evidence = new List<string>(verdict.Evidence);
        string? fingerprint = null;

        // 展开图元（含子树）世界坐标
        var prims = node.AllPrimitives().ToList();
        var worldPoints = prims.SelectMany(EntityPoints).ToList();

        double[][]? footprint = null;
        double? geoW = null, geoD = null;
        PointDto? position = null;
        Envelope worldBox = new();
        foreach (var p in worldPoints) worldBox.ExpandToInclude(p);

        if (!worldBox.IsNull)
        {
            footprint = AabbPolygon(worldBox);
            position = new PointDto
            {
                X = Math.Round(worldBox.Centre.X, 1),
                Y = Math.Round(worldBox.Centre.Y, 1)
            };

            // 局部轴尺寸：世界点逆变换回块局部坐标系取跨度
            var inv = node.Transform.Inverse();
            var localBox = new Envelope();
            foreach (var p in worldPoints) localBox.ExpandToInclude(inv.Apply(p));
            geoW = Math.Round(localBox.MaxX - localBox.MinX, 0);
            geoD = Math.Round(localBox.MaxY - localBox.MinY, 0);
            fingerprint = ComputeFingerprint(node);

            if (FurnitureClassifier.PlausibleFurnitureSize(geoW.Value, geoD.Value))
                evidence.Add("geometry");
        }
        else
        {
            flags.Add("EMPTY_GEOMETRY");
            flags.Add("UNCERTAIN_FOOTPRINT");
        }

        if (node.QualityFlags.Contains("UNRESOLVED_BLOCK"))
            flags.Add("UNCERTAIN_FOOTPRINT");
        if (verdict.Category == "UNKNOWN")
            flags.Add("UNKNOWN_CATEGORY");

        // 房间归属：锚点 PIP + 轮廓重叠复核
        string? roomId = null;
        if (position != null && footprint != null)
        {
            (roomId, var roomFlag) = RoomAssigner.Assign(
                new Coordinate(position.X, position.Y), ToNts(footprint), rooms, targetRegion);
            if (roomFlag != null) flags.Add(roomFlag);
        }
        else if (!targetRegion.IsNull && !worldBox.IsNull && !worldBox.Intersects(targetRegion))
        {
            flags.Add("OUT_OF_TARGET_SCENE");
        }

        string detectionConfidence =
            flags.Contains("UNRESOLVED_BLOCK") || worldBox.IsNull ? "low"
            : evidence.Count >= 2 ? "high"
            : "mid";

        return new FurnitureDto
        {
            FurnitureId = ParserInfo.FurnitureId(node.InsertPath),
            SourceKind = "INSERT",
            SourceHandles = node.InsertPath.ToList(),
            InsertPath = node.InsertPath.ToList(),
            Category = verdict.Category,
            SemanticLabel = FurnitureClassifier.SemanticLabel(node.BlockName, node.Attributes),
            RoomId = roomId,
            BlockName = string.IsNullOrWhiteSpace(node.BlockName) ? null : node.BlockName,
            Position = position,
            RotationDeg = Math.Round(node.Transform.RotationDeg(), 1),
            FootprintPolygon = footprint,
            GeometryWidthMm = geoW,
            GeometryDepthMm = geoD,
            AnnotatedWidthMm = null,
            AnnotatedDepthMm = null,
            EffectiveWidthMm = null,
            EffectiveDepthMm = null,
            HeightMm = null,
            DimensionSource = null,
            SemanticSource = verdict.SemanticSource,
            Evidence = evidence.Distinct().ToList(),
            QualityFlags = flags.Distinct().ToList(),
            DetectionConfidence = detectionConfidence,
            CategoryConfidence = verdict.Category == "UNKNOWN" ? "low" : verdict.CategoryConfidence,
            GeometryFingerprint = fingerprint
        };
    }

    /// <summary>
    /// P1-A 候选清洗：无有效几何或包围盒最大边 &lt; MinFurnitureDimMm 的候选标记 REJECTED
    /// （FURNITURE_SIZE_TOO_SMALL），仅从默认展示/视觉分组中排除，不物理删除。
    /// </summary>
    private static int ApplyCandidateHygiene(List<FurnitureDto> items, ParserOptions opt)
    {
        int rejected = 0;
        foreach (var f in items)
        {
            bool empty = f.QualityFlags.Contains("EMPTY_GEOMETRY");
            double maxDim = Math.Max(f.GeometryWidthMm ?? 0, f.GeometryDepthMm ?? 0);
            if (!empty && maxDim >= opt.MinFurnitureDimMm) continue;
            f.CandidateStatus = "REJECTED";
            if (!empty && !f.QualityFlags.Contains("FURNITURE_SIZE_TOO_SMALL"))
                f.QualityFlags.Add("FURNITURE_SIZE_TOO_SMALL");
            rejected++;
        }
        return rejected;
    }

    /// <summary>
    /// P1-B 去重分组 + P1-C 视觉语义分组。
    /// 去重键 = 块名 + 几何指纹 + 局部尺寸 + 旋转角 + 位置网格（DuplicatePositionToleranceMm）：
    /// 同一位置同一几何的多份实例（多方案叠图）成组，组内首个为 PRIMARY，其余 DUPLICATE
    /// （抑制展示但不删除）。视觉分组键 = 块名 + 指纹 + 尺寸（跨位置/旋转共享一次视觉识别）。
    /// </summary>
    private static Dictionary<string, List<FlatEntity>> AssignVisionGroups(
        List<FurnitureDto> items,
        Dictionary<string, BlockResolver.InsertNode> nodeById,
        ParserOptions opt,
        List<string>? diagnostics)
    {
        // P1-C 视觉语义分组（仅 VALID 候选参与视觉识别）
        var visionGroups = new Dictionary<string, List<FurnitureDto>>();
        foreach (var f in items.Where(x => x.CandidateStatus == "VALID"))
        {
            f.VisionGroupId = "VG-" + ParserInfo.ShortHash(
                $"{f.BlockName}|{f.GeometryFingerprint}|{f.GeometryWidthMm}|{f.GeometryDepthMm}");
            if (!visionGroups.TryGetValue(f.VisionGroupId, out var list))
                visionGroups[f.VisionGroupId] = list = new List<FurnitureDto>();
            list.Add(f);
        }

        // P1-B 去重分组
        double tol = opt.DuplicatePositionToleranceMm;
        var dupGroups = items
            .Where(f => f.CandidateStatus == "VALID" && f.Position != null)
            .GroupBy(f => $"{f.BlockName}|{f.GeometryFingerprint}|{f.GeometryWidthMm}x{f.GeometryDepthMm}"
                          + $"|rot{Math.Round(f.RotationDeg)}"
                          + $"|{Math.Round(f.Position!.X / tol)}|{Math.Round(f.Position!.Y / tol)}")
            .Where(g => g.Count() > 1)
            .ToList();
        foreach (var g in dupGroups)
        {
            var members = g.OrderBy(x => x.FurnitureId).ToList();
            var dgId = "DG-" + ParserInfo.ShortHash(g.Key);
            var ids = members.Select(x => x.FurnitureId).ToList();
            foreach (var (m, i) in members.Select((x, i) => (x, i)))
            {
                m.DuplicateGroupId = dgId;
                m.DuplicateStatus = i == 0 ? "PRIMARY" : "DUPLICATE";
                m.DuplicateCount = members.Count;
                m.SourceInstances = ids;
            }
        }
        if (dupGroups.Count > 0)
            diagnostics?.Add($"去重分组（P1-B）：{dupGroups.Count} 组、{dupGroups.Sum(g => g.Count() - 1)} 件重复副本标记 DUPLICATE（PRIMARY 保留，副本抑制不删除）");

        // 视觉代表图元（每个 VG 取首个 VALID 实例，转块局部坐标）
        var prims = new Dictionary<string, List<FlatEntity>>();
        foreach (var kv in visionGroups)
        {
            var rep = kv.Value[0];
            if (nodeById.TryGetValue(rep.FurnitureId, out var node))
                prims[kv.Key] = ToLocalPrimitives(node);
        }
        return prims;
    }

    /// <summary>块定义几何指纹：局部坐标（逆变换归一化）量化到 1mm 后的规范字符串哈希。</summary>
    private static string ComputeFingerprint(BlockResolver.InsertNode node)
    {
        var inv = node.Transform.Inverse();
        var entityKeys = new List<string>();
        foreach (var e in node.AllPrimitives())
        {
            var sb = new StringBuilder();
            sb.Append(e.Kind).Append(':');
            var pts = new List<Coordinate>();
            if (e.Points != null) pts.AddRange(e.Points.Select(inv.Apply));
            if (e.Rings != null) foreach (var r in e.Rings) pts.AddRange(r.Select(inv.Apply));
            if (e.TextPos != null) pts.Add(inv.Apply(e.TextPos));
            foreach (var p in pts.OrderBy(x => x.X).ThenBy(x => x.Y))
                sb.Append((long)Math.Round(p.X)).Append(',').Append((long)Math.Round(p.Y)).Append(';');
            entityKeys.Add(sb.ToString());
        }
        entityKeys.Sort(StringComparer.Ordinal);
        return ParserInfo.ShortHash(string.Concat(entityKeys));
    }

    /// <summary>节点展开图元转块局部坐标（旋转/平移/缩放归一化），供 isolation 视图渲染。</summary>
    private static List<FlatEntity> ToLocalPrimitives(BlockResolver.InsertNode node)
    {
        var inv = node.Transform.Inverse();
        var list = new List<FlatEntity>();
        foreach (var e in node.AllPrimitives())
        {
            var c = new FlatEntity
            {
                Kind = e.Kind,
                Layer = e.Layer,
                Closed = e.Closed,
                HatchIsSolid = e.HatchIsSolid,
                HatchPattern = e.HatchPattern,
                Text = e.Text
            };
            if (e.Points != null) c.Points = e.Points.Select(inv.Apply).ToList();
            if (e.Rings != null) c.Rings = e.Rings.Select(r => r.Select(inv.Apply).ToList()).ToList();
            if (e.TextPos != null) c.TextPos = inv.Apply(e.TextPos);
            var bb = new Envelope();
            if (c.Points != null) foreach (var p in c.Points) bb.ExpandToInclude(p);
            if (c.Rings != null) foreach (var r in c.Rings) foreach (var p in r) bb.ExpandToInclude(p);
            if (c.TextPos != null) bb.ExpandToInclude(c.TextPos);
            c.BBox = bb;
            list.Add(c);
        }
        return list;
    }

    private static IEnumerable<Coordinate> EntityPoints(FlatEntity e)
    {
        if (e.Points != null) foreach (var p in e.Points) yield return p;
        if (e.Rings != null) foreach (var r in e.Rings) foreach (var p in r) yield return p;
        if (e.TextPos != null) yield return e.TextPos;
        if (e.Points == null && e.Rings == null && e.TextPos == null && !e.BBox.IsNull)
        {
            yield return new Coordinate(e.BBox.MinX, e.BBox.MinY);
            yield return new Coordinate(e.BBox.MaxX, e.BBox.MaxY);
        }
    }

    private static double[][] AabbPolygon(Envelope bb) => new[]
    {
        new[] { Math.Round(bb.MinX, 1), Math.Round(bb.MinY, 1) },
        new[] { Math.Round(bb.MaxX, 1), Math.Round(bb.MinY, 1) },
        new[] { Math.Round(bb.MaxX, 1), Math.Round(bb.MaxY, 1) },
        new[] { Math.Round(bb.MinX, 1), Math.Round(bb.MaxY, 1) },
        new[] { Math.Round(bb.MinX, 1), Math.Round(bb.MinY, 1) },
    };

    internal static Polygon ToNts(double[][] closedRing)
    {
        var gf = NtsGeometryServices.Instance.CreateGeometryFactory();
        return gf.CreatePolygon(closedRing.Select(p => new Coordinate(p[0], p[1])).ToArray());
    }
}

/// <summary>
/// 家具 → 房间归属：锚点 point-in-polygon 判定 + 轮廓重叠复核。
/// 落开放边界 / 跨房间 / 目标区外 → roomId=null 并给出 qualityFlag。
/// </summary>
public static class RoomAssigner
{
    /// <summary>返回 (roomId, qualityFlag)；无法归属时 roomId=null 且 flag 非空。</summary>
    public static (string? RoomId, string? Flag) Assign(
        Coordinate anchor,
        Polygon footprint,
        IReadOnlyList<(string RoomId, Polygon Geom)> rooms,
        Envelope targetRegion)
    {
        bool inTarget = targetRegion.IsNull || targetRegion.Contains(anchor);
        if (rooms.Count == 0)
            return (null, inTarget ? "NO_ROOM_MATCH" : "OUT_OF_TARGET_SCENE");

        // ① 锚点 PIP
        var point = footprint.Factory.CreatePoint(anchor);
        var anchorHits = rooms.Where(r => r.Geom.Contains(point) || r.Geom.Touches(point)).ToList();

        // ② 轮廓重叠复核：footprint 与各房间的交集占比
        double fpArea = footprint.Area;
        var overlaps = rooms
            .Select(r =>
            {
                double inter;
                try { inter = r.Geom.Intersection(footprint).Area; }
                catch { inter = 0; }
                return (r.RoomId, Ratio: fpArea > 1e-9 ? inter / fpArea : 0);
            })
            .Where(x => x.Ratio > 0.2)
            .OrderByDescending(x => x.Ratio)
            .ToList();

        if (anchorHits.Count == 1)
        {
            var hit = anchorHits[0];
            // 跨房间复核：另一房间也覆盖 footprint ≥20% 且锚点房覆盖不足 50%
            if (overlaps.Count > 1 && overlaps.Any(o => o.RoomId != hit.RoomId)
                && overlaps.First(o => o.RoomId == hit.RoomId).Ratio < 0.5)
                return (null, "CROSS_ROOM");
            return (hit.RoomId, null);
        }
        if (anchorHits.Count > 1)
            return (null, "CROSS_ROOM");

        // 锚点不在任何房间：开放边界 / 目标区外
        return (null, inTarget ? "NO_ROOM_MATCH" : "OUT_OF_TARGET_SCENE");
    }
}
