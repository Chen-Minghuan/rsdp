using ACadSharp;
using ACadSharp.Entities;
using CSMath;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>Dictionary 计数辅助。</summary>
internal static class DictionaryCounter
{
    public static void Increment<T>(this Dictionary<T, int> dict, T key) where T : notnull
        => dict[key] = dict.GetValueOrDefault(key) + 1;

    public static void Increment(this Dictionary<string, int> dict, Entity e)
        => dict.Increment(e.GetType().Name);
}

/// <summary>
/// 实体抽取：模型空间实体 → 扁平几何（坐标 × 单位系数 → 毫米）。
/// P0 起 INSERT 由 <see cref="BlockResolver"/> 递归展开（保留实例树与溯源），此处不再兜底为 Other 空壳。
/// 不支持/降级的实体类型计入 UnsupportedEntitySummary，不再静默跳过。
/// </summary>
public static class EntityExtractor
{
    public class ExtractionResult
    {
        public List<FlatEntity> Entities { get; } = new();
        public Dictionary<string, int> Unsupported { get; } = new();
        public int Failed { get; set; }
        public double MinZ { get; set; } = double.MaxValue;
        public double MaxZ { get; set; } = double.MinValue;
    }

    public static List<FlatEntity> Extract(CadDocument doc, double scale, List<string>? diagnostics = null)
        => ExtractDetailed(doc.ModelSpace.Entities, scale, CancellationToken.None, diagnostics).Entities;

    public static ExtractionResult ExtractDetailed(IEnumerable<Entity> source, double scale, CancellationToken ct, List<string>? diagnostics = null)
    {
        var result = new ExtractionResult();
        int i = 0;
        foreach (var e in source)
        {
            if ((++i & 0xFF) == 0) ct.ThrowIfCancellationRequested();
            if (e.IsInvisible) continue;
            try
            {
                SampleZ(e, scale, result);
                var flat = Convert(e, scale, null, result.Unsupported);
                if (flat != null)
                {
                    flat.SourceHandle = e.Handle.ToString("X");
                    result.Entities.Add(flat);
                }
            }
            catch (OperationCanceledException) { throw; }
            catch
            {
                result.Failed++;
                var tn = e.GetType().Name;
                result.Unsupported[tn] = result.Unsupported.GetValueOrDefault(tn) + 1;
            }
        }
        if (result.Failed > 0) diagnostics?.Add($"实体抽取跳过 {result.Failed} 个无法处理的实体");
        return result;
    }

    public static FlatEntity? Convert(Entity e, double scale, Affine2D? transform = null, Dictionary<string, int>? unsupported = null)
        => e.IsInvisible ? null : Build(e, scale, unsupported) is { } f ? Tx(f, transform) : null;

    private static FlatEntity? Build(Entity e, double scale, Dictionary<string, int>? unsupported)
    {
        var layer = e.Layer?.Name ?? "";
        switch (e)
        {
            case Line l:
            {
                var pts = new List<Coordinate> { Pt(l.StartPoint, scale), Pt(l.EndPoint, scale) };
                return new FlatEntity { Kind = FlatKind.Line, Layer = layer, Points = pts, BBox = BBoxOf(pts) };
            }
            case LwPolyline lp:
            {
                var pts = ExpandVertices(
                    lp.Vertices.Select(v => (Pt(v.Location, scale), v.Bulge)).ToList(),
                    lp.IsClosed);
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = lp.IsClosed, BBox = BBoxOf(pts) };
            }
            case Polyline2D p2:
            {
                var pts = ExpandVertices(
                    p2.Vertices.Select(v => (Pt(v.Location, scale), v.Bulge)).ToList(),
                    p2.IsClosed);
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = p2.IsClosed, BBox = BBoxOf(pts) };
            }
            case Polyline3D p3:
            {
                var pts = p3.Vertices.Select(v => Pt(v.Location, scale)).ToList();
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = p3.IsClosed, BBox = BBoxOf(pts) };
            }
            case Arc arc:
            {
                var pts = SampleArc(arc.Center, arc.Radius, arc.StartAngle, arc.EndAngle, scale);
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = false, BBox = BBoxOf(pts) };
            }
            case Circle circle:
            {
                var pts = SampleArc(circle.Center, circle.Radius, 0, 2 * Math.PI, scale);
                pts.Add(pts[0]);
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = true, BBox = BBoxOf(pts) };
            }
            case Ellipse el:
            {
                var pts = SampleEllipse(el.Center, el.MajorAxisEndPoint, el.RadiusRatio, el.StartParameter, el.EndParameter, scale);
                if (Math.Abs(el.EndParameter - el.StartParameter) >= 2 * Math.PI - 1e-6) pts.Add(pts[0]);
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, Closed = false, BBox = BBoxOf(pts) };
            }
            case Hatch h:
            {
                var rings = new List<List<Coordinate>>();
                foreach (var path in h.Paths)
                {
                    var ring = PathToRing(path, scale);
                    if (ring is { Count: >= 4 }) rings.Add(ring);
                }
                if (rings.Count == 0) return null;
                var bb = new Envelope();
                foreach (var r in rings) foreach (var c in r) bb.ExpandToInclude(c);
                return new FlatEntity
                {
                    Kind = FlatKind.Hatch,
                    Layer = layer,
                    Rings = rings,
                    HatchIsSolid = h.IsSolid,
                    HatchPattern = h.Pattern?.Name ?? "",
                    BBox = bb
                };
            }
            case MText mt:
            {
                var text = MTextCleaner.Clean(mt.PlainText ?? mt.Value);
                var pos = Pt(mt.InsertPoint, scale);
                return new FlatEntity { Kind = FlatKind.Text, Layer = layer, Text = text, TextPos = pos, BBox = new Envelope(pos) };
            }
            case TextEntity t:
            {
                var text = MTextCleaner.Clean(t.Value);
                var pos = Pt(t.InsertPoint, scale);
                return new FlatEntity { Kind = FlatKind.Text, Layer = layer, Text = text, TextPos = pos, BBox = new Envelope(pos) };
            }
            case ACadSharp.Entities.Dimension dim:
            {
                // 尺寸标注实体：取测量值与文字中点（关联房间用）
                var pos = Pt(dim.TextMiddlePoint, scale);
                if (double.IsNaN(pos.X) || double.IsNaN(pos.Y)) return null;
                return new FlatEntity
                {
                    Kind = FlatKind.Dimension,
                    Layer = layer,
                    Measure = dim.Measurement * scale,
                    TextPos = pos,
                    BBox = new Envelope(pos)
                };
            }
            case Spline sp:
            {
                // SPLINE 降级为拟合点/控制点采样（支持矩阵：PARTIAL）
                var src = sp.FitPoints is { Count: > 1 } ? sp.FitPoints : sp.ControlPoints;
                if (src is not { Count: > 1 })
                {
                    unsupported?.Increment(e);
                    var bb0 = SafeBBox(e, scale);
                    return bb0 == null ? null : new FlatEntity { Kind = FlatKind.Other, Layer = layer, BBox = bb0 };
                }
                var pts = src.Select(p => Pt(p, scale)).ToList();
                return new FlatEntity { Kind = FlatKind.Polyline, Layer = layer, Points = pts, BBox = BBoxOf(pts) };
            }
            case Insert:
                // INSERT 由 BlockResolver 递归展开，保留实例树与溯源，不在此兜底。
                return null;
            // WIPEOUT/SOLID（P1-E）：深化图常用 WIPEOUT 填充墙身（遮盖底色），边界即墙体实心轮廓；
            // 按实心 HATCH 等价物输出，交给既有墙体判定/栅格填充管线
            case ACadSharp.Entities.Wipeout wo:
            {
                var verts = wo.ClipBoundaryVertices?.ToList();
                if (verts == null || verts.Count < 3)
                {
                    unsupported?.Increment(e);
                    var bb0 = SafeBBox(e, scale);
                    return bb0 == null ? null : new FlatEntity { Kind = FlatKind.Other, Layer = layer, BBox = bb0 };
                }
                var ring = verts.Select(v => new Coordinate(v.X * scale, v.Y * scale)).ToList();
                if (ring[0].Distance(ring[^1]) > 1e-6) ring.Add(ring[0]); // 闭合
                var wbb = BBoxOf(ring);
                return new FlatEntity
                {
                    Kind = FlatKind.Hatch, Layer = layer, Rings = new List<List<Coordinate>> { ring },
                    HatchIsSolid = true, HatchPattern = "WIPEOUT", BBox = wbb
                };
            }
            case ACadSharp.Entities.Solid so:
            {
                var ring = new List<Coordinate>
                {
                    new(so.FirstCorner.X * scale, so.FirstCorner.Y * scale),
                    new(so.SecondCorner.X * scale, so.SecondCorner.Y * scale),
                    // SOLID 角点顺序为 Z 形（1-2-4-3），补齐闭合
                    new(so.FourthCorner.X * scale, so.FourthCorner.Y * scale),
                    new(so.ThirdCorner.X * scale, so.ThirdCorner.Y * scale),
                    new(so.FirstCorner.X * scale, so.FirstCorner.Y * scale)
                };
                var sbb = BBoxOf(ring);
                return new FlatEntity
                {
                    Kind = FlatKind.Hatch, Layer = layer, Rings = new List<List<Coordinate>> { ring },
                    HatchIsSolid = true, HatchPattern = "SOLID", BBox = sbb
                };
            }
            default:
            {
                unsupported?.Increment(e);
                var bbox = SafeBBox(e, scale);
                return bbox == null ? null : new FlatEntity { Kind = FlatKind.Other, Layer = layer, BBox = bbox };
            }
        }
    }

    /// <summary>对转换结果应用块局部→世界变换（块展开图元用）。</summary>
    private static FlatEntity Tx(FlatEntity f, Affine2D? transform)
    {
        if (transform is not { } tx) return f;
        if (f.Points != null) f.Points = f.Points.Select(tx.Apply).ToList();
        if (f.Rings != null) f.Rings = f.Rings.Select(r => r.Select(tx.Apply).ToList()).ToList();
        if (f.TextPos != null) f.TextPos = tx.Apply(f.TextPos);
        var bb = new Envelope();
        if (f.Points != null) foreach (var c in f.Points) bb.ExpandToInclude(c);
        if (f.Rings != null) foreach (var r in f.Rings) foreach (var c in r) bb.ExpandToInclude(c);
        if (f.TextPos != null) bb.ExpandToInclude(f.TextPos);
        if (!bb.IsNull) f.BBox = bb;
        return f;
    }

    /// <summary>Z 范围采样（平面性检查用；尽力而为，失败忽略）。</summary>
    public static void SampleZ(Entity e, double scale, ExtractionResult acc)
    {
        try
        {
            var b = e.GetBoundingBox();
            Feed(b.Min.Z * scale, acc);
            Feed(b.Max.Z * scale, acc);
        }
        catch { /* 无可靠 Z 信息时跳过 */ }
    }

    private static void Feed(double z, ExtractionResult acc)
    {
        if (double.IsNaN(z) || double.IsInfinity(z)) return;
        if (z < acc.MinZ) acc.MinZ = z;
        if (z > acc.MaxZ) acc.MaxZ = z;
    }

    private static Envelope? SafeBBox(Entity e, double scale)
    {
        try
        {
            var b = e.GetBoundingBox();
            if (double.IsNaN(b.Min.X) || double.IsNaN(b.Max.X)) return null;
            return new Envelope(b.Min.X * scale, b.Max.X * scale, b.Min.Y * scale, b.Max.Y * scale);
        }
        catch
        {
            return null;
        }
    }

    private static Coordinate Pt(XYZ v, double s) => new(v.X * s, v.Y * s);
    private static Coordinate Pt(XY v, double s) => new(v.X * s, v.Y * s);

    private static Envelope BBoxOf(IEnumerable<Coordinate> pts)
    {
        var bb = new Envelope();
        foreach (var c in pts) bb.ExpandToInclude(c);
        return bb;
    }

    /// <summary>把带 bulge 的顶点序列离散为直线点列。</summary>
    private static List<Coordinate> ExpandVertices(List<(Coordinate Pt, double Bulge)> verts, bool closed)
    {
        var pts = new List<Coordinate>();
        int n = verts.Count;
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++)
        {
            var (a, bulge) = verts[i];
            var (b, _) = verts[(i + 1) % n];
            pts.Add(a);
            if (Math.Abs(bulge) > 1e-9)
                pts.AddRange(ExpandBulge(a, b, bulge));
        }
        if (!closed && n > 0) pts.Add(verts[n - 1].Pt);
        if (closed && n > 0) pts.Add(pts[0]);
        return pts;
    }

    /// <summary>bulge 圆弧离散：返回 a→b 之间的中间点（不含 a、b）。</summary>
    private static IEnumerable<Coordinate> ExpandBulge(Coordinate a, Coordinate b, double bulge)
    {
        double theta = 4 * Math.Atan(bulge); // 圆心角（带方向）
        double chord = a.Distance(b);
        if (chord < 1e-9) yield break;
        double r = chord / (2 * Math.Sin(Math.Abs(theta) / 2));
        // 圆心：弦中点沿法向偏移 d = r*cos(theta/2)，方向由 bulge 符号决定
        double d = r * Math.Cos(theta / 2);
        double ux = (b.X - a.X) / chord, uy = (b.Y - a.Y) / chord;
        // bulge > 0 逆时针：圆心在弦右侧（(uy,-ux) 方向）
        double cx = (a.X + b.X) / 2 + uy * d;
        double cy = (a.Y + b.Y) / 2 - ux * d;
        double a0 = Math.Atan2(a.Y - cy, a.X - cx);
        int steps = Math.Max(2, (int)Math.Ceiling(Math.Abs(theta) / (Math.PI / 12)));
        for (int i = 1; i < steps; i++)
        {
            double ang = a0 + theta * i / steps;
            yield return new Coordinate(cx + r * Math.Cos(ang), cy + r * Math.Sin(ang));
        }
    }

    /// <summary>HATCH 边界路径 → 闭合点环。</summary>
    private static List<Coordinate>? PathToRing(Hatch.BoundaryPath path, double scale)
    {
        var pts = new List<Coordinate>();
        if (path.IsPolyline)
        {
            var pl = path.Edges.OfType<Hatch.BoundaryPath.Polyline>().FirstOrDefault();
            if (pl == null) return null;
            var verts = pl.Vertices.Select(v => (Pt(v, scale), 0.0)).ToList();
            var bulges = pl.Bulges?.ToList();
            if (bulges != null && bulges.Count == verts.Count)
                for (int i = 0; i < verts.Count; i++) verts[i] = (verts[i].Item1, bulges[i]);
            pts = ExpandVertices(verts, pl.IsClosed);
        }
        else
        {
            foreach (var edge in path.Edges)
            {
                List<Coordinate>? seg = edge switch
                {
                    Hatch.BoundaryPath.Line ln => new List<Coordinate> { Pt(ln.Start, scale), Pt(ln.End, scale) },
                    Hatch.BoundaryPath.Arc arc => SampleArc(arc, scale),
                    Hatch.BoundaryPath.Ellipse el => SampleEllipse(el, scale),
                    Hatch.BoundaryPath.Spline sp => SampleSpline(sp, scale),
                    _ => null
                };
                if (seg == null || seg.Count == 0) continue;
                // 避免相邻边重复点
                int start = (pts.Count > 0 && pts[^1].Distance(seg[0]) < 1e-6) ? 1 : 0;
                for (int i = start; i < seg.Count; i++) pts.Add(seg[i]);
            }
        }
        if (pts.Count < 3) return null;
        if (pts[0].Distance(pts[^1]) > 1e-6) pts.Add(pts[0]);
        return pts.Count >= 4 ? pts : null;
    }

    private static List<Coordinate> SampleArc(Hatch.BoundaryPath.Arc arc, double scale)
    {
        double a0 = arc.StartAngle, a1 = arc.EndAngle;
        if (!arc.CounterClockWise) (a0, a1) = (a1, a0);
        return SampleArc(arc.Center, arc.Radius, a0, a1, scale);
    }

    /// <summary>圆弧离散（CCW，角度弧度，自动处理 a1 &lt; a0 的跨零情况）。</summary>
    private static List<Coordinate> SampleArc(XY center, double radius, double a0, double a1, double scale)
    {
        var pts = new List<Coordinate>();
        while (a1 < a0) a1 += 2 * Math.PI;
        double sweep = a1 - a0;
        int steps = Math.Max(2, (int)Math.Ceiling(sweep / (Math.PI / 12)));
        for (int i = 0; i <= steps; i++)
        {
            double ang = a0 + sweep * i / steps;
            pts.Add(new Coordinate(
                (center.X + radius * Math.Cos(ang)) * scale,
                (center.Y + radius * Math.Sin(ang)) * scale));
        }
        return pts;
    }

    private static List<Coordinate> SampleArc(XYZ center, double radius, double a0, double a1, double scale)
        => SampleArc(new XY(center.X, center.Y), radius, a0, a1, scale);

    private static List<Coordinate> SampleEllipse(Hatch.BoundaryPath.Ellipse el, double scale)
    {
        double a0 = el.StartAngle, a1 = el.EndAngle;
        if (!el.CounterClockWise) (a0, a1) = (a1, a0);
        return SampleEllipse(el.Center, el.MajorAxisEndPoint, el.RadiusRatio, a0, a1, scale);
    }

    /// <summary>椭圆弧离散（主轴向量为相对圆心坐标，短轴 = 主轴旋转 90° × RadiusRatio）。</summary>
    private static List<Coordinate> SampleEllipse(XY center, XY majorAxisEndPoint, double radiusRatio, double a0, double a1, double scale)
    {
        var pts = new List<Coordinate>();
        double mx = majorAxisEndPoint.X, my = majorAxisEndPoint.Y;
        double nx = -my * radiusRatio, ny = mx * radiusRatio;
        while (a1 < a0) a1 += 2 * Math.PI;
        double sweep = a1 - a0;
        int steps = Math.Max(4, (int)Math.Ceiling(sweep / (Math.PI / 16)));
        for (int i = 0; i <= steps; i++)
        {
            double t = a0 + sweep * i / steps;
            pts.Add(new Coordinate(
                (center.X + mx * Math.Cos(t) + nx * Math.Sin(t)) * scale,
                (center.Y + my * Math.Cos(t) + ny * Math.Sin(t)) * scale));
        }
        return pts;
    }

    private static List<Coordinate> SampleEllipse(XYZ center, XYZ majorAxisEndPoint, double radiusRatio, double a0, double a1, double scale)
        => SampleEllipse(new XY(center.X, center.Y), new XY(majorAxisEndPoint.X, majorAxisEndPoint.Y), radiusRatio, a0, a1, scale);

    private static List<Coordinate> SampleSpline(Hatch.BoundaryPath.Spline sp, double scale)
    {
        if (sp.FitPoints is { Count: > 0 })
            return sp.FitPoints.Select(p => Pt(p, scale)).ToList();
        return (sp.ControlPoints ?? new List<XYZ>()).Select(p => Pt(p, scale)).ToList();
    }
}
