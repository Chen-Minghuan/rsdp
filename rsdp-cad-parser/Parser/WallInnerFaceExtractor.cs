using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>墙体内侧边线来源（数值即优先级，越小越可信）。</summary>
public enum InnerFaceSource
{
    /// <summary>完成面类图层（InnerFaceLayerPattern，如「W装饰完成面」）上的线/多段线——现成的墙体内侧边界。</summary>
    FinishLayer = 0,
    /// <summary>实心墙身并集（WallUnion）的边界环——墙体两侧边线就在实心体边界上，贴房间侧即内侧面。</summary>
    WallSolidBoundary = 1,
    /// <summary>双线墙配对：平行且间距落在墙厚窗口内的墙线配成一堵墙的两侧边线。</summary>
    WallLinePair = 2,
    /// <summary>未配对墙线（单线墙/无法确认墙厚的线），最低优先级。</summary>
    WallLineSingle = 3,
}

/// <summary>一段墙体内侧边线（有限线段；吸附时以其所在无限直线为目标）。</summary>
public sealed class InnerFaceSegment
{
    public required Coordinate A { get; init; }
    public required Coordinate B { get; init; }
    /// <summary>规范化方向（|Dir|=1，取向规范化：x 为正，x≈0 时 y 为正；可能与 A→B 反向）。</summary>
    public required Coordinate Dir { get; init; }
    /// <summary>B 相对 A 沿 Dir 的投影参数区间（Dir 规范化后 A→B 可能为负向，故显式存储）。</summary>
    public double TMin { get; set; }
    public double TMax { get; set; }
    public required InnerFaceSource Source { get; init; }
    /// <summary>在集合中的稳定序号（确定性 tie-break 用）。</summary>
    public int Index { get; set; }
    public double Length { get; set; }
    public Envelope Envelope { get; set; } = new();
}

/// <summary>墙体内侧边线集合：线段表 + STRtree 空间索引。</summary>
public sealed class WallInnerFaceSet
{
    public List<InnerFaceSegment> Segments { get; } = new();
    public STRtree<InnerFaceSegment> Tree { get; } = new();

    /// <summary>按包络查询候选线段（调用方自行做精确距离/角度判定）。</summary>
    public IList<InnerFaceSegment> Query(Envelope envelope) => Tree.Query(envelope);
}

/// <summary>
/// 墙体内侧边线（Wall Inner Face）构建。来源按优先级：
/// ① 完成面类图层线（InnerFaceLayerPattern，深化图直接给出装饰完成面）；
/// ② 实心墙身并集（WallUnion）的边界环（墙体的两侧边线 = 实心体边界，房间侧即内侧面）；
/// ③ 双线墙线配对（平行、间距 ∈ [WallPairMinThickMm, WallPairMaxThickMm] 的墙线配成两侧边线；
///    未配对墙线以更低优先级兜底）。
/// 房间侧判定不在此展开（无法脱离具体房间定向），由 RoomWallSnapper 按
/// "最近距离 + 室内侧位移优先"在吸附时完成。
/// </summary>
public static class WallInnerFaceExtractor
{
    /// <summary>共线相邻线段沿墙走向的搭接/间隙合并上限（毫米）：仅吸收制图断口，不跨越门洞断口。</summary>
    private const double CollinearBridgeGapMm = 150;

    /// <summary>每条墙边最多检查的局部候选数，密集区域限流不影响其他区域配对。</summary>
    private const int LocalPairingCandidateLimit = 512;

    /// <summary>
    /// 从指定实体集与墙体提取结果构建墙体内侧边线集合。实体集应与 WallExtractor.Extract
    /// 所用集合一致（各链路各自传入自己的作用域实体）。
    /// </summary>
    public static WallInnerFaceSet Build(
        IEnumerable<FlatEntity> entities,
        WallExtractor.WallExtractionResult walls,
        ParserOptions opt,
        List<string>? diagnostics = null)
    {
        var set = new WallInnerFaceSet();
        var finishRe = new Regex(opt.InnerFaceLayerPattern);
        var raw = new List<InnerFaceSegment>();

        // ① 完成面类图层线
        int finishEntities = 0;
        foreach (var e in entities)
        {
            if (e.Kind is not (FlatKind.Line or FlatKind.Polyline) || e.Points is not { Count: >= 2 }) continue;
            if (!finishRe.IsMatch(e.Layer ?? "")) continue;
            finishEntities++;
            AddPolylineSegments(raw, e.Points, e.Closed, InnerFaceSource.FinishLayer);
        }

        // ② 实心墙身并集边界环
        int boundaryRings = 0;
        if (walls.WallUnion != null)
        {
            foreach (var poly in WallExtractor.Flatten(walls.WallUnion))
            {
                AddRingSegments(poly.ExteriorRing.Coordinates);
                foreach (var hole in poly.InteriorRings) AddRingSegments(hole.Coordinates);
                boundaryRings++;
            }
        }

        // ③ 双线墙线配对（墙线图层线；配对成功 = 一堵墙的两侧边线）
        var wallSegs = new List<InnerFaceSegment>();
        foreach (var pts in walls.WallLines)
            AddPolylineSegments(wallSegs, pts, false, InnerFaceSource.WallLineSingle);
        int limitedSegments = 0;
        int paired = wallSegs.Count == 0 ? 0 : PairWallLines(wallSegs, opt, out limitedSegments);

        foreach (var seg in raw.Concat(wallSegs))
        {
            seg.Index = set.Segments.Count;
            set.Segments.Add(seg);
            set.Tree.Insert(seg.Envelope, seg);
        }
        set.Tree.Build();
        diagnostics?.Add($"墙体内侧面：完成面线 {finishEntities} 实体 → {raw.Count(s => s.Source == InnerFaceSource.FinishLayer)} 段；"
                         + $"实心墙身边界 {boundaryRings} 环 → {raw.Count(s => s.Source == InnerFaceSource.WallSolidBoundary)} 段；"
                         + $"墙线 {wallSegs.Count} 段（配对 {paired} 对）"
                         + (limitedSegments > 0 ? $"，局部候选限流 {limitedSegments} 段" : ""));
        return set;

        void AddRingSegments(Coordinate[] ring)
        {
            var pts = SimplifyCollinear(ring.ToList(), 0.5);
            AddPolylineSegments(raw, pts, true, InnerFaceSource.WallSolidBoundary);
        }
    }

    /// <summary>多段线 → 线段集合（先做共线顶点吸收，closed 时补首尾闭合边）。</summary>
    private static void AddPolylineSegments(List<InnerFaceSegment> output, List<Coordinate> points,
        bool closed, InnerFaceSource source)
    {
        var pts = SimplifyCollinear(points, 0.5);
        if (pts.Count < 2) return;
        // closed 点列可能首尾重复（HATCH 环），去掉重复末点避免零长边
        var work = closed && pts.Count > 1 && pts[0].Distance(pts[^1]) < 1e-6
            ? pts.Take(pts.Count - 1).ToList()
            : pts;
        if (work.Count < 2) return;
        int count = closed && work.Count >= 3 ? work.Count : work.Count - 1;
        var segments = new List<InnerFaceSegment>();
        for (int i = 0; i < count; i++)
        {
            var a = work[i];
            var b = work[(i + 1) % work.Count];
            var seg = MakeSegment(a, b, source);
            if (seg != null) segments.Add(seg);
        }
        output.AddRange(MergeCollinearRuns(segments));
    }

    /// <summary>吸收近共线中间顶点（偏离前后连线 &lt; toleranceMm），消除描图碎点。</summary>
    private static List<Coordinate> SimplifyCollinear(List<Coordinate> pts, double toleranceMm)
    {
        if (pts.Count < 3) return pts;
        bool closed = pts[0].Distance(pts[^1]) < 1e-6;
        var work = closed ? pts.Take(pts.Count - 1).ToList() : new List<Coordinate>(pts);
        var result = new List<Coordinate> { work[0] };
        for (int i = 1; i < work.Count - 1; i++)
        {
            var prev = result[^1];
            var next = work[i + 1];
            if (PointLineDistance(work[i], prev, next) < toleranceMm) continue;
            result.Add(work[i]);
        }
        result.Add(work[^1]);
        if (closed) result.Add(result[0].Copy());
        return result;
    }

    /// <summary>同一多段线内共线且首尾相接（或间隙 ≤ CollinearBridgeGapMm 的搭接）的连续段合并为一段。</summary>
    private static List<InnerFaceSegment> MergeCollinearRuns(List<InnerFaceSegment> segments)
    {
        var merged = new List<InnerFaceSegment>();
        foreach (var seg in segments)
        {
            if (merged.Count > 0)
            {
                var last = merged[^1];
                bool collinear = Math.Abs(Cross(last.Dir, seg.Dir)) < 0.017 // ≈1°
                                 && PointLineDistance(seg.A, last.A, last.B) < 2.0;
                double gap = Math.Min(last.B.Distance(seg.A), last.A.Distance(seg.B));
                if (collinear && gap <= CollinearBridgeGapMm)
                {
                    // 沿方向取两端最远点
                    double tA = Dot(Sub(seg.A, last.A), last.Dir);
                    double tB = Dot(Sub(seg.B, last.A), last.Dir);
                    double tEnd = Dot(Sub(last.B, last.A), last.Dir);
                    double tMax = Math.Max(tEnd, Math.Max(tA, tB));
                    double tMin = Math.Min(0, Math.Min(tA, tB));
                    var na = Add(last.A, Mul(last.Dir, tMin));
                    var nb = Add(last.A, Mul(last.Dir, tMax));
                    var m = MakeSegment(na, nb, last.Source);
                    if (m != null) merged[^1] = m;
                    continue;
                }
            }
            merged.Add(seg);
        }
        return merged;
    }

    /// <summary>
    /// 双线墙配对：平行（夹角 &lt;5°）、线间距 ∈ 墙厚窗口、投影重叠 ≥300mm 的墙线段互配。
    /// 配对成功的两侧边线优先级提升为 WallLinePair。返回配对数。
    /// </summary>
    private static int PairWallLines(List<InnerFaceSegment> segs, ParserOptions opt, out int limitedSegments)
    {
        var tree = new STRtree<int>();
        limitedSegments = 0;
        // 重叠至少 300mm：短段不可能成为配对边，但仍保留供轮廓吸附使用。
        for (int i = 0; i < segs.Count; i++)
            if (segs[i].Length >= 300) tree.Insert(segs[i].Envelope, i);
        tree.Build();
        var pairedFlag = new bool[segs.Count];
        var queryEnv = new Envelope();
        int pairs = 0;
        for (int i = 0; i < segs.Count; i++)
        {
            var s = segs[i];
            if (s.Length < 300) continue;
            queryEnv.Init(s.Envelope);
            queryEnv.ExpandBy(opt.WallPairMaxThickMm);
            var candidates = tree.Query(queryEnv).Where(j => j > i
                && Math.Abs(Dot(s.Dir, segs[j].Dir)) >= 0.996).ToList();
            if (candidates.Count > LocalPairingCandidateLimit)
            {
                limitedSegments++;
                candidates = candidates.OrderBy(j => PointLineDistance(segs[j].A, s.A, s.B))
                    .ThenBy(j => j).Take(LocalPairingCandidateLimit).ToList();
            }
            foreach (var j in candidates)
            {
                var t = segs[j];
                if (Math.Abs(Dot(s.Dir, t.Dir)) < 0.996) continue; // ≈5°
                double dist = PointLineDistance(t.A, s.A, s.B);
                if (dist < opt.WallPairMinThickMm || dist > opt.WallPairMaxThickMm) continue;
                // 投影重叠（沿 s 方向，以 s.A 为原点；s.Dir 规范化后区间不一定是 [0, Length]）
                double t0 = Dot(Sub(t.A, s.A), s.Dir), t1 = Dot(Sub(t.B, s.A), s.Dir);
                double lo = Math.Max(s.TMin, Math.Min(t0, t1)), hi = Math.Min(s.TMax, Math.Max(t0, t1));
                if (hi - lo < 300) continue;
                if (!pairedFlag[i]) { pairedFlag[i] = true; pairs++; }
                if (!pairedFlag[j]) { pairedFlag[j] = true; }
            }
        }
        for (int i = 0; i < segs.Count; i++)
            if (pairedFlag[i])
                segs[i] = new InnerFaceSegment
                {
                    A = segs[i].A, B = segs[i].B, Dir = segs[i].Dir,
                    Source = InnerFaceSource.WallLinePair, Length = segs[i].Length,
                    TMin = segs[i].TMin, TMax = segs[i].TMax, Envelope = segs[i].Envelope
                };
        return pairs;
    }

    private static InnerFaceSegment? MakeSegment(Coordinate a, Coordinate b, InnerFaceSource source)
    {
        double dx = b.X - a.X, dy = b.Y - a.Y;
        double len = Math.Sqrt(dx * dx + dy * dy);
        if (len < 1) return null;
        var dir = new Coordinate(dx / len, dy / len);
        if (dir.X < -1e-9 || (Math.Abs(dir.X) <= 1e-9 && dir.Y < 0)) dir = new Coordinate(-dir.X, -dir.Y);
        double t1 = Dot(Sub(b, a), dir);
        return new InnerFaceSegment
        {
            A = a, B = b, Dir = dir, Source = source, Length = len,
            TMin = Math.Min(0, t1), TMax = Math.Max(0, t1),
            Envelope = new Envelope(a, b)
        };
    }

    internal static double PointLineDistance(Coordinate p, Coordinate a, Coordinate b)
    {
        double dx = b.X - a.X, dy = b.Y - a.Y;
        double len = Math.Sqrt(dx * dx + dy * dy);
        if (len < 1e-9) return p.Distance(a);
        return Math.Abs((p.X - a.X) * dy - (p.Y - a.Y) * dx) / len;
    }

    internal static double Cross(Coordinate u, Coordinate v) => u.X * v.Y - u.Y * v.X;
    internal static double Dot(Coordinate u, Coordinate v) => u.X * v.X + u.Y * v.Y;
    internal static Coordinate Sub(Coordinate a, Coordinate b) => new(a.X - b.X, a.Y - b.Y);
    internal static Coordinate Add(Coordinate a, Coordinate b) => new(a.X + b.X, a.Y + b.Y);
    internal static Coordinate Mul(Coordinate a, double k) => new(a.X * k, a.Y * k);
}
