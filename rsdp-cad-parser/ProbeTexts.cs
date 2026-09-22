using RsdpCadParser.Domain;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>dump 铺贴/标注图层的全部文字及坐标。</summary>
public static class ProbeTexts
{
    public static void Run(string path)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var doc = CadDocumentLoader.Load(File.ReadAllBytes(path), Path.GetFileName(path));
        double scale = CadDocumentLoader.ScaleToMm(doc);
        var entities = EntityExtractor.Extract(doc, scale);
        foreach (var e in entities.Where(e => e.Kind == FlatKind.Text && e.Text != null
                     && (e.Layer.Contains("铺贴") || e.Layer.Contains("文字") || e.Layer.Contains("标注"))
                     && e.TextPos != null
                     && e.BBox.MinX / 1000 > 125 && e.BBox.MinX / 1000 < 155
                     && e.BBox.MinY / 1000 > -40 && e.BBox.MinY / 1000 < -5)
                     .OrderBy(e => e.BBox.MinY).ThenBy(e => e.BBox.MinX))
            Console.WriteLine($"  [{e.Layer}] \"{e.Text}\" @({e.TextPos!.X / 1000:F1},{e.TextPos!.Y / 1000:F1})");
    }
}
