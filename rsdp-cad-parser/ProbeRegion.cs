using ACadSharp.Entities;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>定位面积标注所在子图副本，并分析该区域内的实体/填充构成。</summary>
public static class ProbeRegion
{
    public static void Run(string path)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var doc = CadDocumentLoader.Load(File.ReadAllBytes(path), Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);

        // 1) 面积标注坐标
        var ann = RsdpCadParser.Parser.AnnotationExtractor.ExtractAreaAnnotations(entities);
        Console.WriteLine("===== 面积标注坐标 =====");
        foreach (var a in ann)
            Console.WriteLine($"  {a.AreaM2,7:F2}㎡ [{a.Layer}] @({a.Pos.X / 1000:F1},{a.Pos.Y / 1000:F1})");
        if (ann.Count == 0) return;

        double minX = ann.Min(a => a.Pos.X), maxX = ann.Max(a => a.Pos.X);
        double minY = ann.Min(a => a.Pos.Y), maxY = ann.Max(a => a.Pos.Y);
        Console.WriteLine($"标注 bbox: ({minX / 1000:F1},{minY / 1000:F1})~({maxX / 1000:F1},{maxY / 1000:F1})");

        // 2) 标注区域 +8m  margin 内的实体构成
        double m = 8000;
        var region = new NetTopologySuite.Geometries.Envelope(minX - m, maxX + m, minY - m, maxY + m);
        var inside = entities.Where(e => e.BBox.Intersects(region)).ToList();
        Console.WriteLine($"\n===== 区域内实体 {inside.Count} 个，按图层 =====");
        foreach (var g in inside.GroupBy(e => e.Layer).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  [{g.Key}] {g.Count()} ({string.Join(",", g.GroupBy(x => x.Kind).Select(k => $"{k.Key}×{k.Count()}"))})");

        // 3) 区域内 HATCH 明细
        Console.WriteLine("\n===== 区域内 HATCH 明细 =====");
        foreach (var h in inside.Where(e => e.Kind == FlatKind.Hatch))
        {
            double area = h.Rings!.Sum(r => Shoelace(r)) / 1e6;
            Console.WriteLine($"  [{h.Layer}] solid={h.HatchIsSolid} pattern=\"{h.HatchPattern}\" rings={h.Rings!.Count} 面积={area:F3}㎡ bbox=({h.BBox.MinX / 1000:F1},{h.BBox.MinY / 1000:F1})~({h.BBox.MaxX / 1000:F1},{h.BBox.MaxY / 1000:F1})");
        }

        // 4) 区域内闭合 LWPOLYLINE（潜在的房间轮廓线）
        Console.WriteLine("\n===== 区域内闭合多段线（面积>1㎡） =====");
        foreach (var p in inside.Where(e => e.Kind == FlatKind.Polyline && e.Closed && e.Points != null))
        {
            double area = Shoelace(p.Points!) / 1e6;
            if (area > 1)
                Console.WriteLine($"  [{p.Layer}] 面积={area:F2}㎡ 顶点={p.Points!.Count} bbox=({p.BBox.MinX / 1000:F1},{p.BBox.MinY / 1000:F1})~({p.BBox.MaxX / 1000:F1},{p.BBox.MaxY / 1000:F1})");
        }
    }

    private static double Shoelace(IReadOnlyList<NetTopologySuite.Geometries.Coordinate> ring)
    {
        double a = 0;
        for (int i = 0; i + 1 < ring.Count; i++)
            a += ring[i].X * ring[i + 1].Y - ring[i + 1].X * ring[i].Y;
        return Math.Abs(a) / 2;
    }
}
