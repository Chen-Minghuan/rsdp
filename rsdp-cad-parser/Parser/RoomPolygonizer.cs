using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 空间多边形化。
///
/// 主路径（线网法，金样本实测有效）：墙图层 + 门图层线网（含门扇/门洞弧线）→
/// 端点吸附 → noding → NTS Polygonizer → 最小闭合环 cells →
/// 门洞凹位归并（无文字、&lt;1.5㎡、贴门线的小 cell 并入共享边最长邻居）→ 面积过滤。
///
/// 兜底路径（HATCH 减法，设计文档原方案）：墙体实心并集存在但线网不足时，
/// 墙体外包络减墙体并集 → 连通分量。
///
/// 过滤：面积 ∈ [MinRoomAreaM2, MaxRoomAreaM2]；嵌套包含保留两者并记质量门。
/// </summary>
public static class RoomPolygonizer
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    public class RoomCandidate
    {
        public required Polygon Polygon { get; set; }
        public double AreaM2 { get; set; }
        public double WidthMm { get; set; }
        public double DepthMm { get; set; }
        public Envelope BBox { get; set; } = new();
    }

    public static List<RoomCandidate> BuildRooms(
        WallExtractor.WallExtractionResult walls,
        List<FlatEntity> texts,
        ParserOptions opt,
        List<QualityIssue> issues,
        List<string>? diagnostics = null)
    {
        List<RoomCandidate> rooms;
        var netLines = walls.WallLines.Concat(walls.DoorLines).ToList();
        if (netLines.Count >= 10)
        {
            rooms = BuildByLineNetwork(walls.WallLines, walls.DoorLines, texts, opt, issues, diagnostics);
        }
        else if (walls.WallUnion != null)
        {
            rooms = BuildBySubtraction(walls.WallUnion, opt, issues, diagnostics);
        }
        else
        {
            issues.Add(new QualityIssue { Level = "block", Code = "NO_WALLS_FOUND", Message = "目标子图内未找到墙体（无实心 HATCH 且墙/门图层线不足）" });
            rooms = new List<RoomCandidate>();
        }

        // 嵌套包含检查（阳台套客厅等场景：保留两者并记质量门提示）
        for (int i = 0; i < rooms.Count; i++)
        for (int j = 0; j < rooms.Count; j++)
        {
            if (i == j || rooms[i].AreaM2 <= rooms[j].AreaM2) continue;
            try
            {
                if (rooms[i].Polygon.Contains(rooms[j].Polygon.InteriorPoint))
                {
                    issues.Add(new QualityIssue
                    {
                        Level = "warn",
                        Code = "NESTED_REGION",
                        Message = $"检测到嵌套空间：{rooms[j].AreaM2:F2}㎡ 区域嵌套在 {rooms[i].AreaM2:F2}㎡ 区域内，两者均保留"
                    });
                }
            }
            catch { /* 拓扑异常忽略 */ }
        }

        return rooms.OrderByDescending(r => r.AreaM2).ToList();
    }

    /// <summary>主路径：墙+门线网 → Polygonizer → 门洞凹位归并 → 面积过滤。</summary>
    private static List<RoomCandidate> BuildByLineNetwork(
        List<List<Coordinate>> wallLines,
        List<List<Coordinate>> doorLines,
        List<FlatEntity> texts,
        ParserOptions opt,
        List<QualityIssue> issues,
        List<string>? diagnostics)
    {
        var rooms = new List<RoomCandidate>();
        double snap = opt.SnapToleranceMm;

        var lineStrings = new List<Geometry>();
        foreach (var pts in wallLines.Concat(doorLines))
        {
            var snapped = SnapToGrid(pts, snap);
            if (snapped == null) continue;
            try { lineStrings.Add(Factory.CreateLineString(snapped)); }
            catch { /* 退化线跳过 */ }
        }
        var gapClosures = BuildCollinearGapClosures(lineStrings, opt.LineCloseGapMm, snap);
        lineStrings.AddRange(gapClosures);
        if (lineStrings.Count == 0)
        {
            issues.Add(new QualityIssue { Level = "block", Code = "NO_WALLS_FOUND", Message = "墙/门线全部退化，无法多边形化" });
            return rooms;
        }

        var noded = UnaryUnionOp.Union(lineStrings);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.05).ToList();
        diagnostics?.Add($"线网多边形化：墙线 {wallLines.Count} + 门线 {doorLines.Count}"
                         + $" + 门洞闭合线 {gapClosures.Count}（吸附 {snap}mm）→ cells {cells.Count} 个");

        // P2：原始 cells 全部输出，凹位归并/面积过滤由 SpaceAligner（标注驱动）完成
        foreach (var p in cells)
            rooms.Add(ToCandidate(p, p.Area / 1e6));
        if (rooms.Count == 0)
            issues.Add(new QualityIssue { Level = "block", Code = "NO_CLOSED_REGION", Message = "墙线未能围合出有效空间（断线过多？可调 SnapToleranceMm）" });
        return rooms;
    }

    /// <summary>
    /// 在近似共线的墙线端点之间补短线，封闭门洞后再做 polygonize。
    /// 仅连接方向相反且连接向量沿墙线方向的端点，避免把相邻家具线或墙角斜连起来。
    /// </summary>
    private static List<Geometry> BuildCollinearGapClosures(
        List<Geometry> lineStrings, double maxGapMm, double snapMm)
    {
        var result = new List<Geometry>();
        if (maxGapMm <= 0) return result;

        var endpoints = new List<(Coordinate Point, Coordinate Inward)>();
        foreach (var geometry in lineStrings.OfType<LineString>())
        {
            var coordinates = geometry.Coordinates;
            if (coordinates.Length < 2) continue;
            AddEndpoint(coordinates[0], coordinates[1]);
            AddEndpoint(coordinates[^1], coordinates[^2]);
        }

        double minGapMm = Math.Max(snapMm * 2, 40);
        const double directionCosine = 0.94; // 约 ±20°
        var used = new HashSet<int>();
        var candidates = new List<(int A, int B, double Distance)>();
        for (int i = 0; i < endpoints.Count; i++)
        for (int j = i + 1; j < endpoints.Count; j++)
        {
            var delta = new Coordinate(
                endpoints[j].Point.X - endpoints[i].Point.X,
                endpoints[j].Point.Y - endpoints[i].Point.Y);
            double distance = Math.Sqrt(delta.X * delta.X + delta.Y * delta.Y);
            if (distance < minGapMm || distance > maxGapMm) continue;
            var gapDirection = Normalize(delta);
            var aDirection = Normalize(endpoints[i].Inward);
            var bDirection = Normalize(endpoints[j].Inward);
            // 两条墙边在门洞两侧应朝相反方向延伸，且都与缺口连接线共线。
            if (Dot(aDirection, bDirection) > -directionCosine) continue;
            if (Math.Abs(Dot(aDirection, gapDirection)) < directionCosine
                || Math.Abs(Dot(bDirection, gapDirection)) < directionCosine) continue;
            candidates.Add((i, j, distance));
        }

        foreach (var candidate in candidates.OrderBy(c => c.Distance))
        {
            if (used.Contains(candidate.A) || used.Contains(candidate.B)) continue;
            var a = endpoints[candidate.A].Point;
            var b = endpoints[candidate.B].Point;
            try
            {
                result.Add(Factory.CreateLineString(new[] { a.Copy(), b.Copy() }));
                used.Add(candidate.A);
                used.Add(candidate.B);
            }
            catch { /* 退化闭合线跳过 */ }
        }
        return result;

        void AddEndpoint(Coordinate point, Coordinate neighbor)
        {
            var inward = new Coordinate(neighbor.X - point.X, neighbor.Y - point.Y);
            if (Math.Abs(inward.X) + Math.Abs(inward.Y) > 1e-6)
                endpoints.Add((point, inward));
        }

        static Coordinate Normalize(Coordinate value)
        {
            double length = Math.Sqrt(value.X * value.X + value.Y * value.Y);
            return length > 1e-9
                ? new Coordinate(value.X / length, value.Y / length)
                : new Coordinate();
        }

        static double Dot(Coordinate a, Coordinate b) => a.X * b.X + a.Y * b.Y;
    }

    /// <summary>兜底路径：外包络 − 墙体并集 → 连通分量。</summary>
    private static List<RoomCandidate> BuildBySubtraction(
        Geometry wallUnion, ParserOptions opt, List<QualityIssue> issues, List<string>? diagnostics)
    {
        var rooms = new List<RoomCandidate>();

        Geometry walls = wallUnion;
        if (opt.WallCloseGapMm > 0)
        {
            try
            {
                walls = wallUnion.Buffer(opt.WallCloseGapMm).Buffer(-opt.WallCloseGapMm);
                diagnostics?.Add($"墙体闭运算 d={opt.WallCloseGapMm}mm：{wallUnion.Area / 1e6:F2}㎡ → {walls.Area / 1e6:F2}㎡");
            }
            catch (Exception ex)
            {
                diagnostics?.Add($"墙体闭运算失败（忽略）：{ex.Message}");
                walls = wallUnion;
            }
        }

        var env = walls.EnvelopeInternal;
        double m = opt.EnvelopeMarginMm;
        var frame = Factory.ToGeometry(new Envelope(env.MinX - m, env.MaxX + m, env.MinY - m, env.MaxY + m));
        var frameRing = frame.Boundary;

        Geometry space;
        try
        {
            space = frame.Difference(walls);
        }
        catch (Exception ex)
        {
            issues.Add(new QualityIssue { Level = "block", Code = "NO_CLOSED_REGION", Message = $"空间减法失败：{ex.Message}" });
            return rooms;
        }

        int exterior = 0, outOfRange = 0;
        var allComps = WallExtractor.Flatten(space).ToList();
        foreach (var comp in allComps)
        {
            bool touchesFrame = comp.Boundary.Distance(frameRing) < opt.ExteriorTouchToleranceMm;
            diagnostics?.Add($"  分量：{comp.Area / 1e6:F2}㎡ 顶点{comp.ExteriorRing.NumPoints} 贴外框={touchesFrame}");
            if (touchesFrame) { exterior++; continue; }
            double areaM2 = comp.Area / 1e6;
            if (areaM2 < opt.MinRoomAreaM2 || areaM2 > opt.MaxRoomAreaM2) { outOfRange++; continue; }
            rooms.Add(ToCandidate(comp, areaM2));
        }
        diagnostics?.Add($"空间分量：房间 {rooms.Count}，剔除室外 {exterior}，面积越界 {outOfRange}");
        if (rooms.Count == 0)
            issues.Add(new QualityIssue { Level = "block", Code = "NO_CLOSED_REGION", Message = "墙体减法后无有效闭合空间" });
        return rooms;
    }

    private static Coordinate[]? SnapToGrid(List<Coordinate> pts, double snapMm)
    {
        var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
        var cleaned = new List<Coordinate> { snapped[0] };
        foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
        return cleaned.Count >= 2 ? cleaned.ToArray() : null;
    }

    private static RoomCandidate ToCandidate(Polygon p, double areaM2)
    {
        var bb = p.EnvelopeInternal;
        double dx = bb.MaxX - bb.MinX, dy = bb.MaxY - bb.MinY;
        return new RoomCandidate
        {
            Polygon = p,
            AreaM2 = Math.Round(areaM2, 2),
            WidthMm = Math.Round(Math.Max(dx, dy), 0),
            DepthMm = Math.Round(Math.Min(dx, dy), 0),
            BBox = bb
        };
    }
}
