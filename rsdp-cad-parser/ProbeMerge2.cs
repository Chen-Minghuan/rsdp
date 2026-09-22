using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 合并实验 v2：线网 = 墙图层线 + 门图层线 + 铺贴层 HATCH 边界（门槛石）；
/// 摆动楔合并：面积 < 阈值 且 墙线 backing 占比 < 50% 的小 cell 并入共享边最长的邻居。
/// </summary>
public static class ProbeMerge2
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

        var wallRe = new System.Text.RegularExpressions.Regex(opt.WallLayerPattern);
        var doorRe = new System.Text.RegularExpressions.Regex(@"(?i)门|door");
        var sillRe = new System.Text.RegularExpressions.Regex(@"(?i)铺贴|地面");

        var wallLines = new List<LineString>();
        var netLines = new List<Geometry>();
        void AddPts(List<Coordinate> pts, bool isWall)
        {
            var snapped = Snap(pts, snapMm);
            if (snapped == null) return;
            try
            {
                var ls = Factory.CreateLineString(snapped);
                netLines.Add(ls);
                if (isWall) wallLines.Add(ls);
            }
            catch { }
        }

        foreach (var e in inside)
        {
            if (e.Points is { Count: >= 2 } pts && (e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline)
                && (wallRe.IsMatch(e.Layer) || doorRe.IsMatch(e.Layer)))
                AddPts(pts, wallRe.IsMatch(e.Layer));
            // 铺贴层 HATCH（门槛石/挡水条）边界作为分割线（算墙）
            if (e.Kind == FlatKind.Hatch && e.Rings != null && sillRe.IsMatch(e.Layer))
                foreach (var r in e.Rings) AddPts(r, false); // 门槛石边不算墙，参与分割但允许合并
        }
        Console.WriteLine($"线网 {netLines.Count} 条（墙线 {wallLines.Count}）");

        var noded = UnaryUnionOp.Union(netLines);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var cells = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty && p.Area / 1e6 > 0.05).ToList();
        Console.WriteLine($"cells {cells.Count} 个");

        var wallUnion = UnaryUnionOp.Union(wallLines.Cast<Geometry>().ToList()).Buffer(snapMm * 2 + 10);

        // 邻接表
        int n = cells.Count;
        var tree = new STRtree<int>();
        for (int i = 0; i < n; i++) tree.Insert(cells[i].EnvelopeInternal, i);
        tree.Build();
        var adj = new Dictionary<int, List<(int Other, double Len, Geometry Shared)>>();
        for (int i = 0; i < n; i++)
        {
            foreach (var j in tree.Query(cells[i].EnvelopeInternal))
            {
                if (j <= i) continue;
                Geometry shared;
                try { shared = cells[i].Boundary.Intersection(cells[j].Boundary); } catch { continue; }
                double len = shared.Length;
                if (len < 100) continue;
                if (!adj.ContainsKey(i)) adj[i] = new();
                if (!adj.ContainsKey(j)) adj[j] = new();
                adj[i].Add((j, len, shared));
                adj[j].Add((i, len, shared));
            }
        }

        // 摆动楔合并：小 cell + 墙线 backing < 50% → 并入共享边最长的邻居
        double wallLen(int i, Geometry shared)
        {
            try { return shared.Intersection(wallUnion).Length; } catch { return shared.Length; }
        }
        var mergedAway = new HashSet<int>();
        bool progress = true;
        while (progress)
        {
            progress = false;
            for (int i = 0; i < n; i++)
            {
                if (mergedAway.Contains(i) || !adj.ContainsKey(i)) continue;
                double areaM2 = cells[i].Area / 1e6;
                if (areaM2 >= 2.0) continue;
                var edges = adj[i].Where(e => !mergedAway.Contains(e.Other)).ToList();
                if (edges.Count == 0) continue;
                double totalShared = edges.Sum(e => e.Len);
                double strongLen = edges.Sum(e => wallLen(i, e.Shared));
                if (strongLen / totalShared >= 0.5) continue; // 是真墙围合的小房间（卫生间等），保留
                var best = edges.OrderByDescending(e => e.Len).First();
                // 合并 i → best.Other
                try
                {
                    var u = UnaryUnionOp.Union(new Geometry[] { cells[best.Other], cells[i] });
                    var big = WallExtractor.Flatten(u).OrderByDescending(p => p.Area).First();
                    cells[best.Other] = big;
                }
                catch { continue; }
                mergedAway.Add(i);
                // 邻接转移
                if (!adj.ContainsKey(best.Other)) adj[best.Other] = new();
                foreach (var e in edges.Where(e => e.Other != best.Other))
                {
                    adj[best.Other].Add((e.Other, e.Len, e.Shared));
                    adj[e.Other].Add((best.Other, e.Len, e.Shared));
                }
                progress = true;
            }
        }
        var merged = cells.Where((c, i) => !mergedAway.Contains(i)).ToList();
        Console.WriteLine($"楔合并后 {merged.Count} 个: {string.Join(", ", merged.Select(p => (p.Area / 1e6).ToString("F2")).OrderByDescending(x => double.Parse(x)))}");

        Console.WriteLine("\n===== 面积标注匹配（±3%，按标注点定位 cell） =====");
        int matched = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var pt = Factory.CreatePoint(a.Pos);
            var cell = merged.Where(p => p.Contains(pt) || p.Boundary.Distance(pt) < 100).OrderBy(p => p.Area).FirstOrDefault();
            double cellM2 = (cell?.Area ?? 0) / 1e6;
            bool ok = cell != null && Math.Abs(cellM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  {a.AreaM2,7:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → {(ok ? $"匹配 {cellM2:F2}㎡ ✓" : $"未匹配（所在区域 {cellM2:F2}㎡，差 {(cellM2 - a.AreaM2) / a.AreaM2:+0.0%}）")}");
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
