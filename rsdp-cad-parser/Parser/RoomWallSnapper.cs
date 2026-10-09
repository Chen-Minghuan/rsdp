using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Geometries.Prepared;
using RsdpCadParser.Domain;
using static RsdpCadParser.Parser.WallInnerFaceExtractor;

namespace RsdpCadParser.Parser;

/// <summary>
/// 房间轮廓贴合墙体内侧面（P2-S 核心）：Room Polygon 不再以自身边线为准，
/// 而是把每条边吸附（snap）到 Wall Inner Face 所在直线上——
///   ① 每条房间边在 SnapMaxDistMm 内找方向一致（夹角 ≤ SnapMaxAngleDeg）的内侧面线段，
///      命中则该边改用内侧面的无限直线（同一组几何坐标，无平行偏移、无可见间隙）；
///      候选评分 = 来源优先级 → 距离分桶（40mm）→ 室内侧位移优先 → 精确距离 → 稳定序号；
///   ② 相邻两条已吸附边的顶点 = 两条内侧面直线的交点（roomVertex = wallInnerFaceIntersection），
///      交点超守卫距离或近平行时回退为原顶点在直线上的投影；
///   ③ 两侧已吸附、总长 ≤ DoorStraightenMaxLenMm 的未命中边链（门洞虚拟封口折线）
///      拉直为一条边，消除门洞附近不规则折线；
///   ④ 吸附后共线连续边合并，消除同一直墙上的轻微偏移碎段；
///   ⑤ 硬不变量：不得新增与墙实心（WallUnion）内部的相交；面积变化超过
///      SnapMaxAreaChangeRatio 时整体回退原轮廓并记 WALL_SNAP_REVERTED；
///   ⑥ 未命中内侧面的边（开放区/无墙侧）保持原样，记 WALL_FACE_NOT_FOUND，不虚构边界。
/// 全部比较使用固定容差与稳定序号 tie-break，同输入同输出。
/// </summary>
public static class RoomWallSnapper
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>单个房间的吸附结果。</summary>
    public sealed class RoomSnapResult
    {
        /// <summary>吸附后（或校验失败回退后的）多边形。</summary>
        public required Polygon Polygon { get; set; }
        /// <summary>轮廓是否发生了实际变化（回退后为 false）。</summary>
        public bool Changed { get; set; }
        /// <summary>是否因硬不变量校验失败而回退原轮廓。</summary>
        public bool Reverted { get; set; }
        public int EdgesTotal { get; set; }
        public int EdgesSnapped { get; set; }
        /// <summary>门洞拉直链数（未命中但被拉直的虚拟封口边链）。</summary>
        public int DoorChainsStraightened { get; set; }
        /// <summary>质量提示码（WALL_FACE_NOT_FOUND / WALL_SNAP_REVERTED）。</summary>
        public List<string> Codes { get; } = new();
    }

    /// <summary>一条吸附目标直线（取自 Inner Face 线段所在无限直线）。</summary>
    private sealed class FaceLine
    {
        public required Coordinate P { get; init; }
        public required Coordinate Dir { get; init; }
    }

    /// <summary>
    /// 对单个房间多边形执行内侧面吸附。faces 为空时原样返回。
    /// 仅处理外环；孔洞（墙条/框形空间的内环）原样保留。
    /// </summary>
    public static RoomSnapResult SnapRoom(Polygon room, WallInnerFaceSet faces, Geometry? wallUnion, ParserOptions opt)
    {
        var result = new RoomSnapResult { Polygon = room };
        if (faces.Segments.Count == 0) return result;

        var shellPts = room.ExteriorRing.Coordinates;
        var pts = shellPts.Take(shellPts.Length - 1).Select(c => c.Copy()).ToList();
        if (pts.Count < 3) return result;
        pts = PreMergeCollinear(pts, Math.Max(2, opt.SnapMaxDistMm * 0.1));
        int n = pts.Count;
        if (n < 3) return result;
        result.EdgesTotal = n;

        var prep = PreparedGeometryFactory.Prepare(room);
        var prepWall = wallUnion != null ? PreparedGeometryFactory.Prepare(wallUnion) : null;
        double cosTol = Math.Cos(opt.SnapMaxAngleDeg * Math.PI / 180);

        // ① 逐边匹配内侧面直线
        var lines = new FaceLine?[n];
        for (int i = 0; i < n; i++)
        {
            lines[i] = MatchEdge(pts[i], pts[(i + 1) % n], prep, prepWall, faces, opt, cosTol);
            if (lines[i] != null) result.EdgesSnapped++;
        }
        if (result.EdgesSnapped == 0)
        {
            result.Codes.Add("WALL_FACE_NOT_FOUND");
            return result;
        }

        // 环起点旋转到一条已吸附边：未命中边链不再跨越数组首尾，链识别简化为线性扫描
        int startIdx = Array.FindIndex(lines, l => l != null);
        if (startIdx > 0)
        {
            pts = Enumerable.Range(0, n).Select(k => pts[(startIdx + k) % n]).ToList();
            lines = Enumerable.Range(0, n).Select(k => lines[(startIdx + k) % n]).ToArray();
        }

        // ③ 门洞边链识别：两侧均已吸附的未命中边链，总长 ≤ 上限 → 拉直
        var straightened = new bool[n];
        for (int i = 0; i < n; i++)
        {
            if (lines[i] != null || straightened[i]) continue;
            // 向前找链尾
            int j = i;
            double chainLen = 0;
            while (j < n && lines[j] == null)
            {
                chainLen += pts[j].Distance(pts[(j + 1) % n]);
                j++;
            }
            bool prevSnapped = lines[(i - 1 + n) % n] != null;
            bool nextSnapped = j < n && lines[j] != null;
            // 链绕环一周（i=0 且 j=n）时首尾同为未命中，由 EdgesSnapped==0 提前返回，此处必有命中边
            if (prevSnapped && nextSnapped && chainLen <= opt.DoorStraightenMaxLenMm)
            {
                for (int k = i; k < j; k++) straightened[k] = true;
                result.DoorChainsStraightened++;
            }
            i = j; // for 循环再 +1 → 跳过整条已处理链
        }

        // ② 顶点重算：双侧吸附 → 直线交点；单侧吸附 → 原顶点投影；未吸附 → 原顶点
        double cornerGuard = Math.Max(opt.SnapMaxDistMm * 4, 1200);
        var vertices = new Coordinate[n];
        for (int i = 0; i < n; i++)
        {
            var prev = lines[(i - 1 + n) % n];
            var next = lines[i];
            var orig = pts[i];
            if (prev != null && next != null)
                vertices[i] = IntersectGuarded(prev, next, orig, cornerGuard);
            else if (next != null)
                vertices[i] = Project(orig, next);
            else if (prev != null)
                vertices[i] = Project(orig, prev);
            else
                vertices[i] = orig;
        }

        // 拉直链内部顶点剔除（链起点顶点=投影在链前直线，链终点顶点=投影在链后直线，
        // 两点直连即拉直后的封口边）
        var kept = new List<Coordinate>();
        for (int i = 0; i < n; i++)
        {
            // 顶点 i 是边 i-1 与边 i 的交界；边 i-1 属于拉直链且非链尾顶点时跳过由链逻辑处理
            bool prevStraightened = straightened[(i - 1 + n) % n];
            bool curStraightened = straightened[i];
            if (prevStraightened && curStraightened) continue; // 链内部顶点
            if (prevStraightened && !curStraightened)
            {
                // 链结束顶点：保留（= 投影在边 i 直线上的点，若边 i 已吸附）
                kept.Add(vertices[i]);
                continue;
            }
            kept.Add(vertices[i]);
        }

        // ④ 共线合并 + 重复点清理
        var finalPts = PostMerge(kept);
        if (finalPts.Count < 3)
        {
            result.Codes.Add("WALL_SNAP_REVERTED");
            result.Reverted = true;
            return result;
        }

        // ⑤ 硬不变量校验
        Polygon? snapped = null;
        try
        {
            finalPts.Add(finalPts[0].Copy());
            var ring = Factory.CreateLinearRing(finalPts.ToArray());
            snapped = Factory.CreatePolygon(ring, room.Holes);
            if (!snapped.IsValid || snapped.IsEmpty)
            {
                var fixedPolys = WallExtractor.Flatten(snapped.Buffer(0)).OrderByDescending(p => p.Area).ToList();
                snapped = fixedPolys.Count > 0 ? fixedPolys[0] : null;
            }
        }
        catch { snapped = null; }

        if (snapped == null || snapped.IsEmpty)
        {
            result.Codes.Add("WALL_SNAP_REVERTED");
            result.Reverted = true;
            return result;
        }

        double areaOld = room.Area;
        double areaNew = snapped.Area;
        if (areaOld <= 0 || Math.Abs(areaNew - areaOld) / areaOld > opt.SnapMaxAreaChangeRatio)
        {
            result.Codes.Add("WALL_SNAP_REVERTED");
            result.Reverted = true;
            return result;
        }

        if (wallUnion != null && snapped.EnvelopeInternal.Intersects(wallUnion.EnvelopeInternal))
        {
            double overlapOld = 0, overlapNew = 0;
            try
            {
                overlapOld = room.Intersection(wallUnion).Area;
                overlapNew = snapped.Intersection(wallUnion).Area;
            }
            catch { /* 拓扑异常时不据此回退 */ }
            double tolerance = Math.Max(10_000, areaOld * 0.002); // 0.01㎡ 或面积 0.2%
            if (overlapNew > overlapOld + tolerance)
            {
                result.Codes.Add("WALL_SNAP_REVERTED");
                result.Reverted = true;
                return result;
            }
        }

        result.Polygon = snapped;
        result.Changed = true;
        // 未命中且未被拉直的边 → 保持原样并记质量提示
        for (int i = 0; i < n; i++)
            if (lines[i] == null && !straightened[i])
            {
                result.Codes.Add("WALL_FACE_NOT_FOUND");
                break;
            }
        return result;
    }

    /// <summary>
    /// 批量吸附（编排用）：对每条链路的最终空间逐个 SnapRoom，聚合质量门。
    /// WALL_FACE_NOT_FOUND（部分边无内侧面，保持原样）与 WALL_SNAP_REVERTED（校验失败回退）各记一条 warn。
    /// </summary>
    public static void SnapAll(
        List<AlignedSpace> spaces, WallInnerFaceSet faces, Geometry? wallUnion,
        ParserOptions opt, List<QualityIssue> issues, List<string>? diagnostics, string tag)
    {
        if (spaces.Count == 0) return;
        if (faces.Segments.Count == 0)
        {
            diagnostics?.Add($"{tag}内侧面吸附：无可用墙体内侧边线，保持原轮廓");
            return;
        }
        var faceNotFound = new List<string>();
        var reverted = new List<string>();
        int changedRooms = 0, straightened = 0;
        foreach (var sp in spaces.OrderByDescending(s => s.Polygon.Area))
        {
            var r = SnapRoom(sp.Polygon, faces, wallUnion, opt);
            string name = sp.Label ?? $"未命名({sp.Polygon.Area / 1e6:F1}㎡)";
            if (r.Reverted) { reverted.Add(name); continue; }
            if (r.Changed)
            {
                sp.Polygon = r.Polygon;
                changedRooms++;
                straightened += r.DoorChainsStraightened;
            }
            if (r.Codes.Contains("WALL_FACE_NOT_FOUND"))
                faceNotFound.Add($"{name}({r.EdgesSnapped}/{r.EdgesTotal}边吸附)");
        }
        diagnostics?.Add($"{tag}内侧面吸附：{spaces.Count} 空间 → 贴合 {changedRooms}，门洞拉直 {straightened} 处，"
                         + $"回退 {reverted.Count}，部分边无内侧面 {faceNotFound.Count}");
        if (reverted.Count > 0)
            issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "WALL_SNAP_REVERTED",
                Message = $"{reverted.Count} 个空间吸附到墙体内侧面后校验未过（面积突变/压墙），已回退原轮廓：{string.Join("、", reverted.Take(8))}"
            });
        if (faceNotFound.Count > 0)
            issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "WALL_FACE_NOT_FOUND",
                Message = $"{faceNotFound.Count} 个空间存在未找到墙体内侧面的边（开放区/无墙侧），已保持原轮廓：{string.Join("、", faceNotFound.Take(8))}"
            });
    }

    /// <summary>空间后续被重划时，按最终轮廓更新缺少墙体内侧面的提示，不再沿用旧边数。</summary>
    public static void RefreshFaceNotFoundIssue(List<AlignedSpace> spaces, WallInnerFaceSet faces,
        Geometry? wallUnion, ParserOptions opt, List<QualityIssue> issues)
    {
        issues.RemoveAll(issue => issue.Code == "WALL_FACE_NOT_FOUND");
        if (faces.Segments.Count == 0) return;

        var faceNotFound = new List<string>();
        foreach (var space in spaces.OrderByDescending(space => space.Polygon.Area))
        {
            var result = SnapRoom(space.Polygon, faces, wallUnion, opt);
            if (result.Codes.Contains("WALL_FACE_NOT_FOUND"))
            {
                string name = space.Label ?? $"未命名({space.Polygon.Area / 1e6:F1}㎡)";
                faceNotFound.Add($"{name}({result.EdgesSnapped}/{result.EdgesTotal}边匹配)");
            }
        }
        if (faceNotFound.Count > 0)
            issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "WALL_FACE_NOT_FOUND",
                Message = $"{faceNotFound.Count} 个空间的最终轮廓存在未匹配墙体内侧面的边（开放区/无墙侧），需人工复核：{string.Join("、", faceNotFound.Take(8))}"
            });
    }

    /// <summary>
    /// 单条房间边匹配内侧面直线；无合格候选返回 null。
    /// 候选过滤：方向一致 + 垂直距离 ≤ SnapMaxDistMm + 投影实质重叠；
    /// 有墙实心时加判"室内侧探针（垂足向室内 50mm）不得落在墙实心内"——
    /// 落在墙体内的候选是同一堵墙的外侧面（或相邻房间的内侧面），必须剔除。
    /// 评分 = 来源优先级 → 垂直距离 → 室内侧位移优先 → 稳定序号。
    /// </summary>
    private static FaceLine? MatchEdge(Coordinate a, Coordinate b, IPreparedGeometry room,
        IPreparedGeometry? wallUnion, WallInnerFaceSet faces, ParserOptions opt, double cosTol)
    {
        double len = a.Distance(b);
        if (len < 1) return null;
        var d = new Coordinate((b.X - a.X) / len, (b.Y - a.Y) / len);
        var mid = new Coordinate((a.X + b.X) / 2, (a.Y + b.Y) / 2);

        // 室内侧法向：从中点向两侧各探 1.5mm，在房间内的一侧为室内
        var normal = new Coordinate(-d.Y, d.X);
        var probeIn = Factory.CreatePoint(new Coordinate(mid.X + normal.X * 1.5, mid.Y + normal.Y * 1.5));
        if (!room.Covers(probeIn)) normal = new Coordinate(d.Y, -d.X);

        var queryEnv = new Envelope(a, b);
        queryEnv.ExpandBy(opt.SnapMaxDistMm);
        FaceLine? best = null;
        (int Priority, double Dist, double InteriorDot, int Index)? bestKey = null;
        foreach (var seg in faces.Query(queryEnv))
        {
            if (Math.Abs(Dot(d, seg.Dir)) < cosTol) continue;
            double perp = PointLineDistance(mid, seg.A, seg.B);
            if (perp > opt.SnapMaxDistMm) continue;
            // 投影重叠：边在线段方向上的投影与线段参数区间 [TMin,TMax]（两端各放宽 SnapMaxDistMm）需有实质重叠
            double t0 = Dot(Sub(a, seg.A), seg.Dir), t1 = Dot(Sub(b, seg.A), seg.Dir);
            double lo = Math.Min(t0, t1), hi = Math.Max(t0, t1);
            double overlap = Math.Min(hi, seg.TMax + opt.SnapMaxDistMm) - Math.Max(lo, seg.TMin - opt.SnapMaxDistMm);
            if (overlap < Math.Max(100, len * 0.25)) continue;

            var line = new FaceLine { P = seg.A, Dir = seg.Dir };
            var foot = Project(mid, line);
            // 墙面归属判定：室内侧探针落在墙实心内 → 该线是墙的外侧面/邻室内侧面，剔除
            if (wallUnion != null)
            {
                var inPoint = Factory.CreatePoint(new Coordinate(
                    foot.X + normal.X * 50, foot.Y + normal.Y * 50));
                if (wallUnion.Covers(inPoint)) continue;
            }

            // 室内侧位移：边中点 → 直线垂足 的位移在室内法向上的投影（贴近房间一侧的墙面优先）
            double interiorDot = (foot.X - mid.X) * normal.X + (foot.Y - mid.Y) * normal.Y;
            var key = ((int)seg.Source, perp, -interiorDot, seg.Index);
            if (bestKey == null || Compare(key, bestKey.Value) < 0)
            {
                bestKey = key;
                best = line;
            }
        }
        return best;
    }

    private static int Compare((int, double, double, int) x, (int, double, double, int) y)
    {
        int c = x.Item1.CompareTo(y.Item1);
        if (c != 0) return c;
        c = x.Item2.CompareTo(y.Item2);
        if (c != 0) return c;
        c = x.Item3.CompareTo(y.Item3);
        if (c != 0) return c;
        return x.Item4.CompareTo(y.Item4);
    }

    /// <summary>两条内侧面直线求交；近平行或交点超守卫距离时回退为原顶点在前一直线上的投影。</summary>
    private static Coordinate IntersectGuarded(FaceLine prev, FaceLine next, Coordinate orig, double guardMm)
    {
        double det = Cross(prev.Dir, next.Dir);
        if (Math.Abs(det) < 0.05) return Project(orig, prev); // ≈3° 内视为平行
        var w = Sub(next.P, prev.P);
        double t = Cross(w, next.Dir) / det;
        var hit = Add(prev.P, Mul(prev.Dir, t));
        return hit.Distance(orig) <= guardMm ? hit : Project(orig, prev);
    }

    /// <summary>点到直线垂足。</summary>
    private static Coordinate Project(Coordinate p, FaceLine line)
    {
        double t = Dot(Sub(p, line.P), line.Dir);
        return Add(line.P, Mul(line.Dir, t));
    }

    /// <summary>吸附前预合并：吸收偏离前后连线 &lt; toleranceMm 的近共线中间顶点（碎段/描图抖动）。</summary>
    private static List<Coordinate> PreMergeCollinear(List<Coordinate> pts, double toleranceMm)
    {
        if (pts.Count < 4) return pts;
        var result = new List<Coordinate>();
        int n = pts.Count;
        for (int i = 0; i < n; i++)
        {
            var prev = result.Count > 0 ? result[^1] : pts[(i - 1 + n) % n];
            var next = pts[(i + 1) % n];
            if (result.Count >= 2 || i > 0)
            {
                if (PointLineDistance(pts[i], prev, next) < toleranceMm
                    && prev.Distance(next) > 1e-6) continue;
            }
            result.Add(pts[i]);
        }
        // 首尾可能仍可合并（环起点是碎点）：最多再吸收两轮
        for (int round = 0; round < 2 && result.Count >= 4; round++)
        {
            if (PointLineDistance(result[0], result[^1], result[1]) < toleranceMm)
                result.RemoveAt(0);
            if (PointLineDistance(result[^1], result[^2], result[0]) < toleranceMm)
                result.RemoveAt(result.Count - 1);
        }
        return result;
    }

    /// <summary>吸附后清理：去重复点（&lt;0.5mm）+ 共线连续边精确合并（&lt;1mm），消除同一直线碎段。</summary>
    private static List<Coordinate> PostMerge(List<Coordinate> pts)
    {
        var dedup = new List<Coordinate>();
        foreach (var p in pts)
            if (dedup.Count == 0 || p.Distance(dedup[^1]) >= 0.5) dedup.Add(p);
        if (dedup.Count > 1 && dedup[0].Distance(dedup[^1]) < 0.5) dedup.RemoveAt(dedup.Count - 1);
        if (dedup.Count < 3) return dedup;

        var result = new List<Coordinate>();
        int n = dedup.Count;
        for (int i = 0; i < n; i++)
        {
            var prev = result.Count > 0 ? result[^1] : dedup[(i - 1 + n) % n];
            var next = dedup[(i + 1) % n];
            if ((result.Count >= 2 || i > 0) && prev.Distance(next) > 1e-6
                && PointLineDistance(dedup[i], prev, next) < 1.0) continue;
            result.Add(dedup[i]);
        }
        for (int round = 0; round < 2 && result.Count >= 4; round++)
        {
            if (PointLineDistance(result[0], result[^1], result[1]) < 1.0) result.RemoveAt(0);
            if (result.Count >= 4 && PointLineDistance(result[^1], result[^2], result[0]) < 1.0)
                result.RemoveAt(result.Count - 1);
        }
        return result;
    }
}
