using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 网格线过滤实验：铺贴图层的地砖网格线（≥3 条等距平行线）剔除后，
/// 剩余铺贴线（房间/砖型分界）并入 墙+门 线网做多边形化。
/// </summary>
public static class ProbeGridFilter
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    public static void Run(string path, double snapMm, string? cellsPng)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);
        var opt = new ParserOptions { SnapToleranceMm = snapMm };
        var annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));
        var target = TargetSelector.Select(entities, annotations, opt);
        var inside = entities.Where(e => e.BBox.Intersects(target.Region)).ToList();

        var wallDoorRe = new System.Text.RegularExpressions.Regex(opt.WallLayerPattern + "|(?i)门|door");
        var tileRe = new System.Text.RegularExpressions.Regex(@"(?i)铺贴|地面");

        var netPts = new List<List<Coordinate>>();
        var tileLines = new List<List<Coordinate>>();
        foreach (var e in inside)
        {
            if (e.Points is not { Count: >= 2 } pts) continue;
            if (e.Kind != FlatKind.Line && e.Kind != FlatKind.Polyline) continue;
            if (wallDoorRe.IsMatch(e.Layer)) netPts.Add(pts);
            else if (tileRe.IsMatch(e.Layer)) tileLines.Add(pts);
            if (e.Kind == FlatKind.Hatch && e.Rings != null && tileRe.IsMatch(e.Layer))
                foreach (var r in e.Rings) netPts.Add(r); // 门槛石边界保留
        }

        // 网格过滤：铺贴线中属于"≥3 条等距平行线"的剔除
        var kept = FilterGridLines(tileLines, out int dropped);
        Console.WriteLine($"铺贴线 {tileLines.Count} 条 → 网格剔除 {dropped}，保留 {kept.Count}");
        netPts.AddRange(kept);

        var lineStrings = new List<Geometry>();
        foreach (var pts in netPts)
        {
            var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
            var cleaned = new List<Coordinate> { snapped[0] };
            foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
            if (cleaned.Count < 2) continue;
            try { lineStrings.Add(Factory.CreateLineString(cleaned.ToArray())); } catch { }
        }
        var noded = UnaryUnionOp.Union(lineStrings);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.05).ToList();
        Console.WriteLine($"cells {cells.Count} 个: {string.Join(", ", cells.Select(p => (p.Area / 1e6).ToString("F2")).OrderByDescending(double.Parse).Take(25))}");

        Console.WriteLine("\n===== 面积标注匹配（±3%，按标注点定位 cell） =====");
        int matched = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var pt = Factory.CreatePoint(a.Pos);
            var cell = cells.Where(p => p.Contains(pt) || p.Boundary.Distance(pt) < 100).OrderBy(p => p.Area).FirstOrDefault();
            double cellM2 = (cell?.Area ?? 0) / 1e6;
            bool ok = cell != null && Math.Abs(cellM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  {a.AreaM2,7:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → {(ok ? $"匹配 {cellM2:F2}㎡ ✓" : $"未匹配（所在区域 {cellM2:F2}㎡）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{annotations.Count}");
        if (cellsPng != null) ProbeLines.RenderCellsPublic(cellsPng, cells, annotations, target.Region);
    }

    /// <summary>
    /// 剔除等距平行网格线：一条直线若存在另外两条平行线分别位于 ±d 与 ±2d（d∈[150,1300]mm，容差 30mm），
    /// 则判定为地砖网格线剔除。按线独立判定，不受跨房间偏移交错影响。
    /// </summary>
    public static List<List<Coordinate>> FilterGridLines(List<List<Coordinate>> lines, out int dropped)
    {
        var hOffs = new List<double>();
        var vOffs = new List<double>();
        var tagged = new List<(bool IsH, double Offset, List<Coordinate> Pts, bool IsLine)>();
        foreach (var pts in lines)
        {
            if (pts.Count == 2)
            {
                double dx = Math.Abs(pts[1].X - pts[0].X), dy = Math.Abs(pts[1].Y - pts[0].Y);
                if (dy < 1)
                {
                    double y = (pts[0].Y + pts[1].Y) / 2;
                    hOffs.Add(y);
                    tagged.Add((true, y, pts, true));
                    continue;
                }
                if (dx < 1)
                {
                    double x = (pts[0].X + pts[1].X) / 2;
                    vOffs.Add(x);
                    tagged.Add((false, x, pts, true));
                    continue;
                }
            }
            tagged.Add((false, 0, pts, false)); // 非直线段直接保留
        }

        bool IsGrid(bool isH, double off, List<double> offs)
        {
            for (int i = 0; i < offs.Count; i++)
            {
                double d = offs[i] - off;
                if (d is < 150 or > 1300) continue;
                foreach (int sign in new[] { 1, -1 })
                {
                    bool has1 = offs.Any(o => Math.Abs(o - (off + sign * d)) < 30);
                    bool has2 = offs.Any(o => Math.Abs(o - (off + sign * 2 * d)) < 30);
                    if (has1 && has2) return true;
                }
            }
            return false;
        }

        var kept = new List<List<Coordinate>>();
        dropped = 0;
        foreach (var (isH, off, pts, isLine) in tagged)
        {
            if (isLine && IsGrid(isH, off, isH ? hOffs : vOffs)) dropped++;
            else kept.Add(pts);
        }
        return kept;
    }
}
