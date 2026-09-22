using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>地砖网格块实验：铺贴层线网 polygonize → cells 按共享边连通成块 → 块面积比对标注。</summary>
public static class ProbeTiles
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

        var tileRe = new System.Text.RegularExpressions.Regex(@"(?i)铺贴|地面");
        var netLines = new List<Geometry>();
        foreach (var e in inside)
        {
            if (!tileRe.IsMatch(e.Layer)) continue;
            if (e.Points is { Count: >= 2 } pts && (e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline))
            {
                var snapped = Snap(pts, snapMm);
                if (snapped != null) { try { netLines.Add(Factory.CreateLineString(snapped)); } catch { } }
            }
            if (e.Kind == FlatKind.Hatch && e.Rings != null)
                foreach (var r in e.Rings)
                {
                    var snapped = Snap(r, snapMm);
                    if (snapped != null) { try { netLines.Add(Factory.CreateLineString(snapped)); } catch { } }
                }
        }
        Console.WriteLine($"铺贴线网 {netLines.Count} 条");
        var noded = UnaryUnionOp.Union(netLines);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.02).ToList();
        Console.WriteLine($"cells {cells.Count} 个");

        // 连通成块：共享边 > 100mm
        int n = cells.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        var tree = new STRtree<int>();
        for (int i = 0; i < n; i++) tree.Insert(cells[i].EnvelopeInternal, i);
        tree.Build();
        for (int i = 0; i < n; i++)
            foreach (var j in tree.Query(cells[i].EnvelopeInternal))
            {
                if (j <= i) continue;
                double len;
                try { len = cells[i].Boundary.Intersection(cells[j].Boundary).Length; } catch { continue; }
                if (len > 100) { int a = Find(i), b = Find(j); if (a != b) parent[a] = b; }
            }
        var patches = cells.Select((c, i) => (c, root: Find(i)))
            .GroupBy(x => x.root)
            .Select(g => g.Sum(x => x.c.Area) / 1e6)
            .Where(a => a > 0.3).OrderByDescending(a => a).ToList();
        Console.WriteLine($"网格块 {patches.Count} 个: {string.Join(", ", patches.Select(a => a.ToString("F2")))}");

        Console.WriteLine("\n===== 面积标注匹配（±3%，集合匹配） =====");
        int matched = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var best = patches.OrderBy(x => Math.Abs(x - a.AreaM2)).FirstOrDefault();
            bool ok = patches.Count > 0 && Math.Abs(best - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  标注 {a.AreaM2,7:F2}㎡ → {(ok ? $"匹配 {best:F2}㎡ ✓" : $"未匹配（最近 {best:F2}㎡，差 {(best - a.AreaM2) / a.AreaM2:+0.0%}）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{annotations.Count}");
    }

    private static Coordinate[]? Snap(List<Coordinate> pts, double snapMm)
    {
        var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
        var cleaned = new List<Coordinate> { snapped[0] };
        foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
        return cleaned.Count >= 2 ? cleaned.ToArray() : null;
    }
}
