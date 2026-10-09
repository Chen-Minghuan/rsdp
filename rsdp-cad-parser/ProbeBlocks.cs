using ACadSharp;
using ACadSharp.Entities;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 块结构摸底：模型空间实体类型分布、INSERT 按块名/图层统计、
/// 嵌套 INSERT、块定义大小、XREF 标志位、INSUNITS。
/// </summary>
public static class ProbeBlocks
{
    public static void Run(string file)
    {
        var doc = CadDocumentLoader.Load(File.ReadAllBytes(file), Path.GetFileName(file));
        Console.WriteLine($"INSUNITS={doc.Header.InsUnits}（ScaleToMm={CadDocumentLoader.ScaleToMm(doc)}）");

        foreach (var br in doc.BlockRecords)
            foreach (var e in br.Entities.Where(e => e.IsInvisible))
                if (e is Hatch h) Console.WriteLine($"HIDDEN {br.Name} {e.Layer.Name} {e.GetType().Name} {h.IsSolid} {h.Pattern?.Name}");
        if (Environment.GetEnvironmentVariable("WALL_TRACE_OUT") is { } output) {
            var parsed = CadParsePipeline.Parse(File.ReadAllBytes(file), Path.GetFileName(file));
            foreach (var br in doc.BlockRecords)
                foreach (var e in br.Entities.Where(e => e.IsInvisible)) e.IsInvisible = false;
            var resolved = BlockResolver.Resolve(doc, CadDocumentLoader.ScaleToMm(doc), new ParserOptions(), CancellationToken.None);
            var prims = EntityExtractor.Extract(doc, CadDocumentLoader.ScaleToMm(doc)).Concat(resolved.ExpandedEntities).ToList();
            foreach (var e in prims.Where(e => e.Kind == RsdpCadParser.Domain.FlatKind.Polyline && e.Layer.Contains("墙") && e.BBox.Intersects(new NetTopologySuite.Geometries.Envelope(parsed.DrawingBounds!.MinX,parsed.DrawingBounds.MaxX,parsed.DrawingBounds.MinY,parsed.DrawingBounds.MaxY)))) Console.WriteLine($"CLOSED closed={e.Closed} pts={e.Points?.Count} gap={(e.Points?.Count > 1 ? e.Points[0].Distance(e.Points[^1]) : -1)} {e.Layer} {e.BlockDefinitionName} {e.SourceHandle} {e.BBox}");
            var preview = CadPreviewRenderer.Render(prims, parsed.Preview!.Bounds!);
            File.WriteAllBytes(output, Convert.FromBase64String(preview.PngBase64!));
            foreach (var g in prims.Where(e => e.Kind == RsdpCadParser.Domain.FlatKind.Hatch && e.BBox.Intersects(new NetTopologySuite.Geometries.Envelope(parsed.DrawingBounds!.MinX,parsed.DrawingBounds.MaxX,parsed.DrawingBounds.MinY,parsed.DrawingBounds.MaxY))).GroupBy(e => e.BlockDefinitionName + "/" + e.Layer)) Console.WriteLine($"TRACE {g.Key} {g.Count()}");
        }
        foreach (var h in doc.ModelSpace.Entities.OfType<Hatch>()) Console.WriteLine($"MODEL-HATCH {h.Layer.Name} solid={h.IsSolid} pattern={h.Pattern?.Name} paths={h.Paths.Count} converted={EntityExtractor.Convert(h, 1, Affine2D.Identity) != null}");
        var typeCounts = new Dictionary<string, int>();
        var insertsByBlock = new Dictionary<string, int>();
        var insertsByLayer = new Dictionary<string, int>();
        int nested = 0;
        foreach (var e in doc.ModelSpace.Entities)
        {
            var tn = e.GetType().Name;
            typeCounts[tn] = typeCounts.GetValueOrDefault(tn) + 1;
            if (e is Insert ins)
            {
                var bn = ins.Block?.Name ?? "<null>";
                insertsByBlock[bn] = insertsByBlock.GetValueOrDefault(bn) + 1;
                var ln = ins.Layer?.Name ?? "<null>";
                insertsByLayer[ln] = insertsByLayer.GetValueOrDefault(ln) + 1;
            }
        }
        Console.WriteLine("== 模型空间实体类型 ==");
        foreach (var kv in typeCounts.OrderByDescending(k => k.Value))
            Console.WriteLine($"  {kv.Key}: {kv.Value}");

        Console.WriteLine($"== INSERT 按块名（共 {insertsByBlock.Values.Sum()} 个 INSERT，{insertsByBlock.Count} 种块） ==");
        foreach (var kv in insertsByBlock.OrderByDescending(k => k.Value))
        {
            int defEnt = 0, nestedIns = 0;
            try
            {
                var br = doc.BlockRecords[kv.Key];
                defEnt = br.Entities.Count();
                nestedIns = br.Entities.OfType<Insert>().Count();
                nested += nestedIns * kv.Value;
            }
            catch { }
            Console.WriteLine($"  [{kv.Value,4}×] {kv.Key}（定义图元 {defEnt}，嵌套 INSERT {nestedIns}）");
        }
        Console.WriteLine("== INSERT 按图层 ==");
        foreach (var kv in insertsByLayer.OrderByDescending(k => k.Value))
            Console.WriteLine($"  [{kv.Value,4}×] {kv.Key}");
        Console.WriteLine($"嵌套 INSERT 实例合计（按顶层实例展开估算）: {nested}");

        Console.WriteLine("== 块定义与 XREF 标志 ==");
        foreach (var br in doc.BlockRecords)
        {
            int flags = (int)br.Flags;
            bool isXref = (flags & 4) != 0 || (flags & 8) != 0;
            bool xrefResolved = (flags & 32) != 0;
            if (isXref || br.Name.StartsWith("*") is false)
                Console.WriteLine($"  {br.Name}: flags=0x{flags:X} ent={SafeCount(br)} xref={isXref} resolved={xrefResolved}");
        }

        Console.WriteLine("== 全部图层 ==");
        foreach (var layer in doc.Layers)
            Console.WriteLine($"  {layer.Name}");
    }

    private static int SafeCount(ACadSharp.Tables.BlockRecord br)
    {
        try { return br.Entities.Count(); } catch { return -1; }
    }
}
