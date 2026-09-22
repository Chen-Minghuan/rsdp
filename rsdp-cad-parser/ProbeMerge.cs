using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 弱边界合并实验：线网（墙+门+铺贴）→ Polygonizer cells →
/// 相邻 cell 共享边若无"强边界"（墙/门线图层） backing 则合并 → 与标注比对。
/// </summary>
public static class ProbeMerge
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

        var strongRe = new System.Text.RegularExpressions.Regex(opt.WallLayerPattern + "|(?i)门|door");
        var networkRe = new System.Text.RegularExpressions.Regex(opt.WallLayerPattern + "|(?i)门|door|铺贴|地面|天花");

        var strongLines = new List<Geometry>();
        var netLines = new List<Geometry>();
        foreach (var e in inside)
        {
            if (e.Points is not { Count: >= 2 } pts) continue;
            if (e.Kind != FlatKind.Line && e.Kind != FlatKind.Polyline) continue;
            var snapped = Snap(pts, snapMm);
            if (snapped == null) continue;
            if (networkRe.IsMatch(e.Layer))
            {
                try
                {
                    var ls = Factory.CreateLineString(snapped);
                    netLines.Add(ls);
                    if (strongRe.IsMatch(e.Layer)) strongLines.Add(ls);
                }
                catch { }
            }
        }
        Console.WriteLine($"线网 {netLines.Count} 条（强边界 {strongLines.Count}）");

        var noded = UnaryUnionOp.Union(netLines);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.05).ToList();
        Console.WriteLine($"cells {cells.Count} 个");

        // 强边界覆盖缓冲
        var strongUnion = UnaryUnionOp.Union(strongLines).Buffer(snapMm * 2 + 10);

        // 邻接 + 弱边合并（并查集）
        int n = cells.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        void Union(int a, int b) { a = Find(a); b = Find(b); if (a != b) parent[a] = b; }

        var tree = new STRtree<int>();
        for (int i = 0; i < n; i++) tree.Insert(cells[i].EnvelopeInternal, i);
        tree.Build();
        int weakEdges = 0, strongEdges = 0;
        for (int i = 0; i < n; i++)
        {
            foreach (var j in tree.Query(cells[i].EnvelopeInternal))
            {
                if (j <= i) continue;
                Geometry shared;
                try { shared = cells[i].Boundary.Intersection(cells[j].Boundary); }
                catch { continue; }
                double len = shared.Length;
                if (len < 100) continue; // 共享边 <100mm 不视为相邻
                double strongLen;
                try { strongLen = shared.Intersection(strongUnion).Length; }
                catch { strongLen = len; }
                if (strongLen / len < 0.5) { Union(i, j); weakEdges++; }
                else strongEdges++;
            }
        }
        Console.WriteLine($"邻接边：弱 {weakEdges}（合并）、强 {strongEdges}（保留）");

        var groups = cells.Select((c, i) => (c, root: Find(i))).GroupBy(x => x.root);
        var merged = new List<Polygon>();
        foreach (var g in groups)
        {
            var polys = g.Select(x => x.c).Cast<Geometry>().ToList();
            var u = UnaryUnionOp.Union(polys);
            merged.AddRange(WallExtractor.Flatten(u));
        }
        var areas = merged.Select(p => p.Area / 1e6).Where(a => a > 0.5).OrderByDescending(a => a).ToList();
        Console.WriteLine($"合并后区域 {merged.Count} 个: {string.Join(", ", areas.Select(a => a.ToString("F2")))}");

        Console.WriteLine("\n===== 面积标注匹配（±3%，合并后按标注点定位） =====");
        int matched = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var pt = Factory.CreatePoint(a.Pos);
            var cell = merged.Where(p => p.Contains(pt) || p.Boundary.Distance(pt) < 100).OrderBy(p => p.Area).FirstOrDefault();
            double cellM2 = (cell?.Area ?? 0) / 1e6;
            bool ok = cell != null && Math.Abs(cellM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  {a.AreaM2,7:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → {(ok ? $"匹配 {cellM2:F2}㎡ ✓" : $"未匹配（所在区域 {cellM2:F2}㎡）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{annotations.Count}");

        if (cellsPng != null)
            ProbeLines.RenderCellsPublic(cellsPng, merged, annotations, target.Region);
    }

    private static Coordinate[]? Snap(List<Coordinate> pts, double snapMm)
    {
        var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
        var cleaned = new List<Coordinate> { snapped[0] };
        foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
        return cleaned.Count >= 2 ? cleaned.ToArray() : null;
    }
}
