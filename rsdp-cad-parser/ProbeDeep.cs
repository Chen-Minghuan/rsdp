using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>深度诊断：聚类桥接实体 + 各簇 HATCH 明细。</summary>
public static class ProbeDeep
{
    public static void Run(string path)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var diag = new List<string>();
        var entities = EntityExtractor.Extract(doc, scale, diag);
        Console.WriteLine($"实体总数 {entities.Count}");

        // 1) 超大包围盒实体（可能是跨子图桥接者）
        Console.WriteLine("\n===== 包围盒对角线 > 10m 的实体分布 =====");
        var big = entities.Where(e => Math.Sqrt(Math.Pow(e.BBox.MaxX - e.BBox.MinX, 2) + Math.Pow(e.BBox.MaxY - e.BBox.MinY, 2)) > 10_000).ToList();
        foreach (var g in big.GroupBy(e => (e.Layer, e.Kind)).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  [{g.Key.Layer}] {g.Key.Kind} ×{g.Count()}");
        foreach (var e in big.Where(e => e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline).Take(15))
            Console.WriteLine($"    {e.Kind} [{e.Layer}] span {(e.BBox.MaxX - e.BBox.MinX) / 1000:F1}×{(e.BBox.MaxY - e.BBox.MinY) / 1000:F1}m @({e.BBox.MinX / 1000:F1},{e.BBox.MinY / 1000:F1})");

        // 2) 不同 gap 下的簇划分
        foreach (double gap in new[] { 10_000.0, 5_000, 3_000, 2_000 })
        {
            var opt = new ParserOptions { ClusterGapMm = gap };
            var clusters = SubDrawingClusterer.Split(entities, opt);
            Console.WriteLine($"\n===== gap={gap / 1000:F0}m → {clusters.Count} 簇 =====");
            foreach (var c in clusters.Take(10))
                Console.WriteLine($"  #{c.Index}: 实体 {c.Entities.Count}, 跨度 {c.SpanXm / 1000:F1}×{c.SpanYm / 1000:F1}m @({c.BBox.MinX / 1000:F1},{c.BBox.MinY / 1000:F1}), 标签 {c.LabelHits}");
        }

        // 3) 每簇 HATCH 明细
        var opt10 = new ParserOptions();
        foreach (var c in SubDrawingClusterer.Split(entities, opt10))
        {
            var hatches = c.Entities.Where(e => e.Kind == FlatKind.Hatch).ToList();
            if (hatches.Count == 0) continue;
            Console.WriteLine($"\n===== 簇 #{c.Index} HATCH {hatches.Count} 个 =====");
            foreach (var g in hatches.GroupBy(h => (h.Layer, h.HatchIsSolid, h.HatchPattern)).OrderByDescending(g => g.Count()))
            {
                var areas = g.Select(h => RingArea(h)).ToList();
                Console.WriteLine($"  [{g.Key.Layer}] solid={g.Key.HatchIsSolid} pattern=\"{g.Key.HatchPattern}\" ×{g.Count()}, 面积 {areas.Min():F2}~{areas.Max():F2}㎡");
            }
        }

        // 4) 每簇房间名标签内容样例
        var labelRe = new System.Text.RegularExpressions.Regex(opt10.RoomLabelPattern);
        foreach (var c in SubDrawingClusterer.Split(entities, opt10))
        {
            var labels = c.Entities.Where(e => e.Kind == FlatKind.Text && e.Text != null && labelRe.IsMatch(e.Text))
                .Select(e => e.Text!).Distinct().Take(20).ToList();
            if (labels.Count > 0)
                Console.WriteLine($"\n簇 #{c.Index} 标签样例: {string.Join(" | ", labels)}");
        }
    }

    private static double RingArea(FlatEntity h)
    {
        double total = 0;
        foreach (var ring in h.Rings!)
        {
            double a = 0;
            for (int i = 0; i + 1 < ring.Count; i++)
                a += ring[i].X * ring[i + 1].Y - ring[i + 1].X * ring[i].Y;
            total += Math.Abs(a) / 2;
        }
        return total / 1e6;
    }
}
