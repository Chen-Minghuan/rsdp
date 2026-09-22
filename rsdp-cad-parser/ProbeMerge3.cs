using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 组合实验 v3：墙+门线网 → Polygonizer cells →
/// ① 门槛石实心（铺贴层 HATCH）从 cells 中减除（铺贴面积量到门槛内侧）；
/// ② 无文字的小 cell（&lt;1.2㎡）并入共享边最长的邻居（门套/壁龛凹位归并）。
/// </summary>
public static class ProbeMerge3
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
        var sillRe = new System.Text.RegularExpressions.Regex(@"(?i)铺贴|地面");

        var netLines = new List<Geometry>();
        var sillPolys = new List<Geometry>();
        foreach (var e in inside)
        {
            if (e.Points is { Count: >= 2 } pts && (e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline) && wallDoorRe.IsMatch(e.Layer))
            {
                var snapped = Snap(pts, snapMm);
                if (snapped != null) { try { netLines.Add(Factory.CreateLineString(snapped)); } catch { } }
            }
            if (e.Kind == FlatKind.Hatch && e.Rings != null && sillRe.IsMatch(e.Layer))
                foreach (var r in e.Rings)
                {
                    try
                    {
                        var ring = Factory.CreateLinearRing(Snap(r, snapMm));
                        var p = Factory.CreatePolygon(ring);
                        if (p.Area > 0) sillPolys.Add(p.IsValid ? p : p.Buffer(0));
                    }
                    catch { }
                }
        }
        var sillUnion = sillPolys.Count > 0 ? UnaryUnionOp.Union(sillPolys) : null;
        Console.WriteLine($"线网 {netLines.Count} 条，门槛石 {sillPolys.Count} 块（{(sillUnion?.Area ?? 0) / 1e6:F2}㎡）");

        var noded = UnaryUnionOp.Union(netLines);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.05).ToList();
        Console.WriteLine($"cells {cells.Count} 个");

        // 文字位置集合（cell 是否含文字）
        var texts = inside.Where(e => e.Kind == FlatKind.Text && e.TextPos != null && !string.IsNullOrWhiteSpace(e.Text)).ToList();
        bool HasText(Polygon p) => texts.Any(t => p.Contains(Factory.CreatePoint(t.TextPos)));

        // 邻接表
        int n = cells.Count;
        var tree = new STRtree<int>();
        for (int i = 0; i < n; i++) tree.Insert(cells[i].EnvelopeInternal, i);
        tree.Build();
        var adj = new Dictionary<int, List<(int Other, double Len)>>();
        for (int i = 0; i < n; i++)
        {
            foreach (var j in tree.Query(cells[i].EnvelopeInternal))
            {
                if (j <= i) continue;
                double len;
                try { len = cells[i].Boundary.Intersection(cells[j].Boundary).Length; } catch { continue; }
                if (len < 100) continue;
                if (!adj.ContainsKey(i)) adj[i] = new();
                if (!adj.ContainsKey(j)) adj[j] = new();
                adj[i].Add((j, len));
                adj[j].Add((i, len));
            }
        }

        // ② 小 cell 归并（无文字、<1.2㎡ → 共享边最长的邻居）
        var gone = new HashSet<int>();
        bool progress = true;
        int mergeRounds = 0;
        while (progress && mergeRounds++ < 50)
        {
            progress = false;
            for (int i = 0; i < n; i++)
            {
                if (gone.Contains(i) || !adj.ContainsKey(i)) continue;
                if (cells[i].Area / 1e6 >= 1.2 || HasText(cells[i])) continue;
                var edges = adj[i].Where(e => !gone.Contains(e.Other)).ToList();
                if (edges.Count == 0) continue;
                var best = edges.OrderByDescending(e => e.Len).First();
                try
                {
                    var u = UnaryUnionOp.Union(new Geometry[] { cells[best.Other], cells[i] });
                    var big = WallExtractor.Flatten(u).OrderByDescending(p => p.Area).FirstOrDefault();
                    if (big == null) continue;
                    cells[best.Other] = big;
                }
                catch { continue; }
                gone.Add(i);
                if (!adj.ContainsKey(best.Other)) adj[best.Other] = new();
                foreach (var e in edges.Where(e => e.Other != best.Other))
                {
                    adj[best.Other].Add((e.Other, e.Len));
                    adj[e.Other].Add((best.Other, e.Len));
                }
                progress = true;
            }
        }
        var merged = cells.Where((c, i) => !gone.Contains(i)).ToList();
        Console.WriteLine($"归并后 {merged.Count} 个");

        // ① 门槛石减除
        var finalCells = new List<Polygon>();
        if (sillUnion != null)
        {
            foreach (var p in merged)
            {
                if (!p.EnvelopeInternal.Intersects(sillUnion.EnvelopeInternal)) { finalCells.Add(p); continue; }
                try
                {
                    var d = p.Difference(sillUnion);
                    var pieces = WallExtractor.Flatten(d).ToList();
                    // 减除可能切成多块：保留最大块 + 其余块若 >1㎡ 也保留
                    finalCells.AddRange(pieces.Where(x => x.Area / 1e6 > 0.05));
                }
                catch { finalCells.Add(p); }
            }
        }
        else finalCells = merged;

        var areas = finalCells.Select(p => p.Area / 1e6).Where(a => a > 0.5).OrderByDescending(a => a).ToList();
        Console.WriteLine($"最终区域: {string.Join(", ", areas.Select(a => a.ToString("F2")))}");

        Console.WriteLine("\n===== 面积标注匹配（±3%，按标注点定位 cell） =====");
        int matched = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var pt = Factory.CreatePoint(a.Pos);
            var cell = finalCells.Where(p => p.Contains(pt) || p.Boundary.Distance(pt) < 100).OrderBy(p => p.Area).FirstOrDefault();
            double cellM2 = (cell?.Area ?? 0) / 1e6;
            bool ok = cell != null && Math.Abs(cellM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  {a.AreaM2,7:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → {(ok ? $"匹配 {cellM2:F2}㎡ ✓" : $"未匹配（所在区域 {cellM2:F2}㎡，差 {(cellM2 - a.AreaM2) / a.AreaM2:+0.0%}）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{annotations.Count}");
        if (cellsPng != null) ProbeLines.RenderCellsPublic(cellsPng, finalCells, annotations, target.Region);
    }

    private static Coordinate[]? Snap(List<Coordinate> pts, double snapMm)
    {
        var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
        var cleaned = new List<Coordinate> { snapped[0] };
        foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
        return cleaned.Count >= 2 ? cleaned.ToArray() : null;
    }
}
