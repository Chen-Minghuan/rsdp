using NetTopologySuite.Geometries;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>逐户型副本诊断：每个标签簇区域的墙体构成与减法房间产出。</summary>
public static class ProbeCopies
{
    public static void Run(string path, double closeGapMm)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);
        var opt = new ParserOptions { WallCloseGapMm = closeGapMm };
        var annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));

        // 与 TargetSelector 相同的标签聚类，但遍历所有簇
        var labelRe = new System.Text.RegularExpressions.Regex(opt.RoomLabelPattern);
        var labels = entities.Where(e => e.Kind == Domain.FlatKind.Text && e.Text != null && e.TextPos != null && labelRe.IsMatch(e.Text)).ToList();
        int n = labels.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (labels[i].TextPos!.Distance(labels[j].TextPos!) <= opt.ClusterGapMm)
                { int a = Find(i), b = Find(j); if (a != b) parent[a] = b; }
        var groups = labels.Select((e, i) => (e, root: Find(i))).GroupBy(x => x.root).Select(g => g.Select(x => x.e).ToList()).ToList();

        int idx = 0;
        foreach (var g in groups.OrderByDescending(g => g.Count))
        {
            var bb = new Envelope();
            foreach (var e in g) bb.ExpandToInclude(e.TextPos!);
            bb.ExpandBy(opt.TargetRegionMarginMm);
            var inside = entities.Where(e => e.BBox.Intersects(bb)).ToList();
            int annCount = annotations.Count(a => bb.Contains(a.Pos));
            var distinct = g.Select(e => e.Text!).Distinct().ToList();

            Console.WriteLine($"\n===== 副本 #{idx++}: 标签 {distinct.Count} 种/{g.Count} 个，标注 {annCount}，区域 ({bb.MinX / 1000:F1},{bb.MinY / 1000:F1})~({bb.MaxX / 1000:F1},{bb.MaxY / 1000:F1}) =====");
            Console.WriteLine($"  标签: {string.Join("|", distinct.Take(18))}");

            var diag = new List<string>();
            var walls = WallExtractor.Extract(inside, opt, diag);
            foreach (var d in diag) Console.WriteLine("  " + d);
            if (walls.WallUnion != null)
            {
                Console.WriteLine($"  墙并集面积 {walls.WallUnion.Area / 1e6:F2}㎡");
                var issues = new List<Domain.QualityIssue>();
                var roomDiag = new List<string>();
                var texts = inside.Where(e => e.Kind == Domain.FlatKind.Text).ToList();
                var rooms = RoomPolygonizer.BuildRooms(walls, texts, opt, issues, roomDiag);
                foreach (var d in roomDiag) Console.WriteLine("  " + d);
                Console.WriteLine("  房间: " + string.Join(", ", rooms.Select(r => r.AreaM2.ToString("F2"))));
            }
        }
    }
}
