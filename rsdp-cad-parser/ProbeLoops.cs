using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Geometries.Prepared;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 闭合面积环探针：统计图纸中闭合多段线 / HATCH 边界环（含块内展开）的
/// 图层分布、面积分布、面积文字包含关系，以及落在 ExcludeWallLayerPattern
/// 排除图层上（当前被主动丢弃）的候选数量。
/// 用途：评估"闭合面积环作为房间候选边界"新链路的数据基础。
/// </summary>
public static class ProbeLoops
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>房间尺寸级闭合环面积窗口（㎡）。</summary>
    private const double RoomMinM2 = 1.0, RoomMaxM2 = 80.0;

    private sealed class Loop
    {
        public required string Source { get; init; }       // PLINE / PLINE-GEO（仅几何闭合）/ HATCH
        public required string Layer { get; init; }
        public bool InBlock { get; init; }
        public string? BlockName { get; init; }
        public required List<Coordinate> Ring { get; init; }
        public double AreaM2 { get; set; }
        public bool Valid { get; set; }                     // NTS 拓扑有效
        public bool Degenerate { get; set; }                // 退化（有效点 <3 / 面积≈0）
        public bool Excluded { get; set; }                  // 命中 ExcludeWallLayerPattern
        public Polygon? Polygon { get; set; }
        public IPreparedGeometry? Prep { get; set; }
        public string DedupeKey { get; set; } = "";
    }

    private sealed class TextItem
    {
        public required string Text { get; init; }
        public required Coordinate Pos { get; init; }
        public required string Layer { get; init; }
        public bool IsAreaText { get; init; }
    }

    public static void Run(string path)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var bytes = File.ReadAllBytes(path);
        var doc = CadDocumentLoader.Load(bytes, Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var opt = new ParserOptions();
        var excludeRe = new Regex(opt.ExcludeWallLayerPattern);

        // 展开后的实体集 = 模型空间直接实体 + BlockResolver 递归展开的块内图元
        var entities = EntityExtractor.Extract(doc, scale);
        int modelCount = entities.Count;
        var resolved = BlockResolver.Resolve(doc, scale, opt, CancellationToken.None);
        entities.AddRange(resolved.ExpandedEntities);
        Console.WriteLine($"文件: {path}");
        Console.WriteLine($"单位系数→mm: {scale}；模型空间实体 {modelCount}，块展开图元 {resolved.ExpandedEntities.Count}（顶层 INSERT {resolved.Roots.Count} 个）");

        var loops = new List<Loop>();
        foreach (var e in entities)
        {
            bool inBlock = e.InsertPath is { Count: > 0 };
            if (e.Kind == FlatKind.Polyline && e.Points is { Count: >= 4 } pts)
            {
                if (e.Closed)
                    loops.Add(MakeLoop("PLINE", e.Layer, inBlock, e.BlockDefinitionName, pts, excludeRe));
                // IsClosed=false 但首末点重合的几何闭合环（CAD 里常见，房间链路同样可利用）
                else if (pts[0].Distance(pts[^1]) <= 1.0)
                    loops.Add(MakeLoop("PLINE-GEO", e.Layer, inBlock, e.BlockDefinitionName, pts, excludeRe));
            }
            if (e.Kind == FlatKind.Hatch && e.Rings != null)
                foreach (var r in e.Rings)
                    loops.Add(MakeLoop("HATCH", e.Layer, inBlock, e.BlockDefinitionName, r, excludeRe));
        }

        var texts = entities
            .Where(e => e.Kind == FlatKind.Text && !string.IsNullOrWhiteSpace(e.Text) && e.TextPos != null)
            .Select(e => new TextItem
            {
                Text = e.Text!.Trim(), Pos = e.TextPos!, Layer = e.Layer,
                IsAreaText = e.Text.Contains('㎡') || e.Text.Contains("面积")
                             || e.Text.Contains("m2", StringComparison.OrdinalIgnoreCase)
            })
            .ToList();

        Console.WriteLine($"\n===== 闭合环总览 =====");
        Console.WriteLine($"闭合环总数 {loops.Count}（PLINE {loops.Count(l => l.Source == "PLINE")}，PLINE-GEO 仅几何闭合 {loops.Count(l => l.Source == "PLINE-GEO")}，HATCH {loops.Count(l => l.Source == "HATCH")}）；" +
                          $"块内展开 {loops.Count(l => l.InBlock)}，模型空间直接 {loops.Count(l => !l.InBlock)}");
        var distinct = loops.GroupBy(l => l.DedupeKey).ToList();
        Console.WriteLine($"去重后唯一环 {distinct.Count}（按 图层+面积+顶点数+包围盒 去重，多副本叠图会重复展开）");
        Console.WriteLine($"拓扑无效（自相交等） {loops.Count(l => !l.Valid)}，退化 {loops.Count(l => l.Degenerate)}");

        Console.WriteLine($"\n===== 图层分布（全部闭合环） =====");
        foreach (var g in loops.GroupBy(l => l.Layer).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  {g.Key,-30} {g.Count(),5}  （房间尺寸级 {g.Count(l => IsRoomScale(l))}）{(excludeRe.IsMatch(g.Key) ? " ← ExcludeWallLayerPattern 排除" : "")}");

        Console.WriteLine($"\n===== 面积分布（㎡） =====");
        (string Name, Func<Loop, bool> Pred)[] buckets =
        {
            ("<1", l => l.AreaM2 < 1),
            ("1-5", l => l.AreaM2 is >= 1 and < 5),
            ("5-20", l => l.AreaM2 is >= 5 and < 20),
            ("20-60", l => l.AreaM2 is >= 20 and < 60),
            (">60", l => l.AreaM2 >= 60),
        };
        foreach (var (name, pred) in buckets)
        {
            var inBucket = loops.Where(pred).ToList();
            Console.WriteLine($"  {name,-6} 全部 {inBucket.Count,5}（PLINE {inBucket.Count(l => l.Source == "PLINE")}，PLINE-GEO {inBucket.Count(l => l.Source == "PLINE-GEO")}，HATCH {inBucket.Count(l => l.Source == "HATCH")}，去重后 {inBucket.Select(l => l.DedupeKey).Distinct().Count()}）");
        }

        Console.WriteLine($"\n===== 面积 Top10 闭合环 =====");
        foreach (var l in loops.OrderByDescending(l => l.AreaM2).Take(10))
            Console.WriteLine($"  {l.Source,-9} {l.AreaM2,9:F2}㎡  图层[{l.Layer}]  {(l.InBlock ? $"块内({l.BlockName})" : "模型空间")}{(l.Valid ? "" : "  [自相交/无效]")}");

        // 房间尺寸级明细
        var roomLoops = loops.Where(IsRoomScale).OrderByDescending(l => l.AreaM2).ToList();
        Console.WriteLine($"\n===== 房间尺寸级闭合环（{RoomMinM2:F0}~{RoomMaxM2:F0}㎡）明细，共 {roomLoops.Count} 条 =====");
        int withAreaText = 0;
        const int maxLines = 300;
        foreach (var l in roomLoops)
        {
            var inside = texts.Where(t => Contains(l, t.Pos)).ToList();
            var areaTexts = inside.Where(t => t.IsAreaText).ToList();
            if (areaTexts.Count > 0) withAreaText++;
            if (roomLoops.Count > maxLines && l.AreaM2 < roomLoops[maxLines - 1].AreaM2) continue;
            string labels = string.Join(" | ", inside.Take(6).Select(t => t.Text.Length > 24 ? t.Text[..24] + "…" : t.Text));
            Console.WriteLine($"  {l.Source,-5} {l.AreaM2,7:F2}㎡  图层[{l.Layer}]  {(l.InBlock ? $"块内({l.BlockName})" : "模型空间"),-20} " +
                              $"{(l.Valid ? "" : "[自相交/无效] ")}{(l.Degenerate ? "[退化] " : "")}{(l.Excluded ? "[排除图层] " : "")}" +
                              $"{(areaTexts.Count > 0 ? "含面积文字✓ " : "")}文字{inside.Count}个{(labels.Length > 0 ? ": " + labels : "")}");
        }
        if (roomLoops.Count > maxLines) Console.WriteLine($"  …（超过 {maxLines} 条，仅按面积截断显示）");
        Console.WriteLine($"房间尺寸级环内含面积文字的环数: {withAreaText}/{roomLoops.Count}（去重后 {DistinctWithAreaText(roomLoops, texts)}/{roomLoops.Select(l => l.DedupeKey).Distinct().Count()}）");

        // 面积标注 ↔ 环 覆盖核对
        var annotations = AnnotationExtractor.DistinctAreas(AnnotationExtractor.ExtractAreaAnnotations(entities));
        Console.WriteLine($"\n===== 面积标注覆盖 =====");
        int covered = 0;
        foreach (var a in annotations.OrderByDescending(x => x.AreaM2))
        {
            var hit = roomLoops.Where(l => Contains(l, a.Pos)).OrderBy(l => Math.Abs(l.AreaM2 - a.AreaM2)).FirstOrDefault();
            bool ok = hit != null;
            if (ok) covered++;
            Console.WriteLine($"  标注 {a.AreaM2,7:F2}㎡ → {(ok ? $"落在环 {hit!.AreaM2:F2}㎡（图层[{hit.Layer}]，差 {(hit.AreaM2 - a.AreaM2) / a.AreaM2:+0.0%;-0.0%}）" : "无环覆盖")}");
        }
        Console.WriteLine($"标注被房间尺寸级闭合环覆盖: {covered}/{annotations.Count}");

        // 排除图层汇总
        Console.WriteLine($"\n===== 排除图层丢弃量 =====");
        var excl = loops.Where(l => l.Excluded).ToList();
        Console.WriteLine($"命中 ExcludeWallLayerPattern 的闭合环: {excl.Count}/{loops.Count}（其中房间尺寸级 {excl.Count(IsRoomScale)}，含面积文字 {DistinctWithAreaText(excl.Where(IsRoomScale).ToList(), texts)}）");
        foreach (var g in excl.GroupBy(l => l.Layer).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  {g.Key,-30} {g.Count(),5}");

        // HATCH 补充：按填充图案分布
        var hatches = loops.Where(l => l.Source == "HATCH").ToList();
        if (hatches.Count > 0)
        {
            Console.WriteLine($"\n===== HATCH 边界环（共 {hatches.Count}）按图层 =====");
            foreach (var g in hatches.GroupBy(l => l.Layer).OrderByDescending(g => g.Count()))
                Console.WriteLine($"  {g.Key,-30} {g.Count(),5}（房间尺寸级 {g.Count(IsRoomScale)}）");
        }
    }

    private static bool IsRoomScale(Loop l) => l.AreaM2 is >= RoomMinM2 and <= RoomMaxM2;

    private static int DistinctWithAreaText(List<Loop> candidates, List<TextItem> texts)
        => candidates.Where(l => texts.Any(t => t.IsAreaText && Contains(l, t.Pos)))
            .Select(l => l.DedupeKey).Distinct().Count();

    private static Loop MakeLoop(string source, string layer, bool inBlock, string? blockName, List<Coordinate> ring, Regex excludeRe)
    {
        var loop = new Loop { Source = source, Layer = layer, InBlock = inBlock, BlockName = blockName, Ring = ring, Excluded = excludeRe.IsMatch(layer) };
        // 保证首尾闭合
        if (ring.Count > 0 && ring[0].Distance(ring[^1]) > 1e-6) ring.Add(ring[0]);
        int distinctPts = ring.Select(c => (Math.Round(c.X, 3), Math.Round(c.Y, 3))).Distinct().Count();
        loop.AreaM2 = Math.Abs(ShoelaceArea(ring)) / 1e6;
        loop.Degenerate = distinctPts < 3 || loop.AreaM2 < 1e-4;
        try
        {
            var poly = Factory.CreatePolygon(ring.ToArray());
            loop.Polygon = poly;
            loop.Valid = poly.IsValid;
            if (loop.Valid) loop.Prep = PreparedGeometryFactory.Prepare(poly);
        }
        catch { loop.Valid = false; }
        var bb = new Envelope();
        foreach (var c in ring) bb.ExpandToInclude(c);
        loop.DedupeKey = $"{source}|{layer}|{loop.AreaM2:F2}|{ring.Count}|{bb.MinX:F0},{bb.MinY:F0}";
        return loop;
    }

    /// <summary>点在环内：优先 NTS prepared covers（含边界），无效多边形回退射线法。</summary>
    private static bool Contains(Loop l, Coordinate p)
    {
        if (l.Prep != null) return l.Prep.Covers(Factory.CreatePoint(p));
        // 射线法（对自相交环也给出近似结果）
        int n = l.Ring.Count;
        bool inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++)
        {
            var a = l.Ring[i]; var b = l.Ring[j];
            if (a.Y > p.Y != b.Y > p.Y && p.X < (b.X - a.X) * (p.Y - a.Y) / (b.Y - a.Y) + a.X)
                inside = !inside;
        }
        return inside;
    }

    private static double ShoelaceArea(List<Coordinate> ring)
    {
        double sum = 0;
        for (int i = 0; i < ring.Count - 1; i++)
            sum += ring[i].X * ring[i + 1].Y - ring[i + 1].X * ring[i].Y;
        return sum / 2;
    }
}
