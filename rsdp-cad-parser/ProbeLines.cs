using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>线网多边形化实验：不同图层组合的 LINE/Polyline → Polygonizer → 与面积标注比对。</summary>
public static class ProbeLines
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    public static void Run(string path, string layerPattern, double snapMm, string? cellsPng = null)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);
        var opt = new ParserOptions();
        var annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));
        var target = TargetSelector.Select(entities, annotations, opt);
        var inside = entities.Where(e => e.BBox.Intersects(target.Region)).ToList();

        var layerRe = new System.Text.RegularExpressions.Regex(layerPattern);
        var lines = new List<List<Coordinate>>();
        foreach (var e in inside)
        {
            if ((e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline) && e.Points is { Count: >= 2 } && layerRe.IsMatch(e.Layer))
                lines.Add(e.Points);
            // HATCH 边界也作为分割线
            if (e.Kind == FlatKind.Hatch && layerRe.IsMatch(e.Layer) && e.Rings != null)
                lines.AddRange(e.Rings);
        }
        Console.WriteLine($"图层 /{layerPattern}/ 线数 {lines.Count}，snap={snapMm}mm");

        var lineStrings = new List<Geometry>();
        foreach (var pts in lines)
        {
            var snapped = pts.Select(p => new Coordinate(Math.Round(p.X / snapMm) * snapMm, Math.Round(p.Y / snapMm) * snapMm)).ToArray();
            // 去掉连续重复点
            var cleaned = new List<Coordinate> { snapped[0] };
            foreach (var p in snapped) if (p.Distance(cleaned[^1]) > 1e-6) cleaned.Add(p);
            if (cleaned.Count < 2) continue;
            try { lineStrings.Add(Factory.CreateLineString(cleaned.ToArray())); } catch { }
        }
        Console.WriteLine($"有效线 {lineStrings.Count}");

        Geometry noded = UnaryUnionOp.Union(lineStrings);
        var polygonizer = new Polygonizer();
        polygonizer.Add(noded);
        var polys = polygonizer.GetPolygons().OfType<Polygon>().Where(p => !p.IsEmpty).ToList();
        Console.WriteLine($"闭合环 {polys.Count} 个：");
        var cells = polys.Select(p => p.Area / 1e6).Where(a => a > 0.3).OrderByDescending(a => a).ToList();
        foreach (var a in cells.Take(40)) Console.WriteLine($"  {a,8:F2}㎡");

        // 匹配
        Console.WriteLine("\n===== 面积标注匹配（±3%） =====");
        int matched = 0;
        foreach (var a in annotations)
        {
            var best = cells.OrderBy(x => Math.Abs(x - a.AreaM2)).FirstOrDefault();
            bool ok = cells.Any() && Math.Abs(best - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  标注 {a.AreaM2,7:F2}㎡ → {(ok ? $"匹配 {best:F2}㎡" : $"未匹配（最近 {(cells.Any() ? best.ToString("F2") : "-")}㎡）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{annotations.Count}");

        // 空间诊断：每个标注点落在哪个 cell，与相邻 cell 的关系
        Console.WriteLine("\n===== 标注点空间定位 =====");
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var pt = Factory.CreatePoint(a.Pos);
            var cell = polys.Where(p => p.Contains(pt) || p.Boundary.Distance(pt) < 50).OrderBy(p => p.Area).FirstOrDefault();
            if (cell == null) { Console.WriteLine($"  {a.AreaM2:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → 无 cell"); continue; }
            double cellM2 = cell.Area / 1e6;
            // 与该 cell 相邻（共享边界）的 cells
            var neighbors = polys.Where(p => p != cell && p.Boundary.Intersects(cell.Boundary) && p.Boundary.Intersection(cell.Boundary).Length > 100)
                .Select(p => p.Area / 1e6).OrderByDescending(x => x).Take(5).ToList();
            Console.WriteLine($"  {a.AreaM2:F2}㎡ @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1}) → cell {cellM2:F2}㎡ (差 {(cellM2 - a.AreaM2) / a.AreaM2:+0.0%;-0.0%})，相邻: [{string.Join(", ", neighbors.Select(x => x.ToString("F2")))}]");
        }

        if (cellsPng != null)
            RenderCells(cellsPng, polys, annotations, target.Region);
    }

    /// <summary>渲染 cells：每个 cell 填充不同灰度，标注点画白色十字。</summary>
    public static void RenderCellsPublic(string outPng, List<Polygon> polys, List<AreaAnnotation> annotations, Envelope region)
        => RenderCells(outPng, polys, annotations, region);

    /// <summary>渲染 cells：每个 cell 填充不同灰度，标注点画白色十字。</summary>
    private static void RenderCells(string outPng, List<Polygon> polys, List<AreaAnnotation> annotations, Envelope region)
    {
        const int W = 1200, H = 1200;
        var img = new byte[W * H];
        double s = Math.Min(W / (region.MaxX - region.MinX), H / (region.MaxY - region.MinY)) * 0.96;
        double ox = (W - s * (region.MaxX - region.MinX)) / 2, oy = (H - s * (region.MaxY - region.MinY)) / 2;
        int Px(double x) => (int)(ox + (x - region.MinX) * s);
        int Py(double y) => (int)(H - (oy + (y - region.MinY) * s));

        var big = polys.Where(p => p.Area / 1e6 > 0.3).OrderByDescending(p => p.Area).Take(20).ToList();
        for (int i = 0; i < big.Count; i++)
        {
            byte v = (byte)(40 + i * 10); // 越大越亮
            var ring = big[i].ExteriorRing.Coordinates;
            // 扫描线填充
            int y0 = Math.Max(0, Py(big[i].EnvelopeInternal.MaxY)), y1 = Math.Min(H - 1, Py(big[i].EnvelopeInternal.MinY));
            for (int y = y0; y <= y1; y++)
            {
                double wy = region.MinY + (H - y - oy) / s;
                var xs = new List<double>();
                for (int k = 0; k + 1 < ring.Length; k++)
                {
                    var (ya, yb) = (ring[k].Y, ring[k + 1].Y);
                    if ((ya <= wy && wy < yb) || (yb <= wy && wy < ya))
                        xs.Add(ring[k].X + (wy - ya) / (yb - ya) * (ring[k + 1].X - ring[k].X));
                }
                xs.Sort();
                for (int k = 0; k + 1 < xs.Count; k += 2)
                {
                    int xa = Math.Max(0, Px(xs[k])), xb = Math.Min(W - 1, Px(xs[k + 1]));
                    for (int x = xa; x <= xb; x++) img[y * W + x] = v;
                }
            }
        }
        foreach (var a in annotations)
        {
            int x = Px(a.Pos.X), y = Py(a.Pos.Y);
            for (int d = -10; d <= 10; d++)
            {
                if (x + d >= 0 && x + d < W && y >= 0 && y < H) img[y * W + x + d] = 255;
                if (x >= 0 && x < W && y + d >= 0 && y + d < H) img[(y + d) * W + x] = 255;
            }
        }
        File.WriteAllBytes(outPng, ProbeRender.PngEncode(img, W, H));
        Console.WriteLine($"cells 已渲染 {outPng}（灰度 40+i*10，面积降序；白色十字=标注点）");
        for (int i = 0; i < big.Count; i++)
            Console.WriteLine($"  灰度{40 + i * 10,3} = {big[i].Area / 1e6,7:F2}㎡ 中心({big[i].Centroid.X / 1000:F1},{big[i].Centroid.Y / 1000:F1})");
    }
}
