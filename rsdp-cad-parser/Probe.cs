using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>诊断 CLI：打印解析流水线全链路中间量，用于调参。</summary>
public static class Probe
{
    public static void Run(string path, double closeGapMm = 0)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        if (!File.Exists(path))
        {
            Console.WriteLine($"文件不存在: {path}");
            return;
        }
        var bytes = File.ReadAllBytes(path);
        var opt = new ParserOptions { WallCloseGapMm = closeGapMm, LineCloseGapMm = closeGapMm };
        var detailed = CadParsePipeline.ParseDetailed(bytes, Path.GetFileName(path), opt);

        Console.WriteLine("===== 诊断 =====");
        foreach (var d in detailed.Diagnostics) Console.WriteLine("  " + d);

        var r = detailed.Result;
        Console.WriteLine($"\n===== 结果 =====\nsuccess={r.Success} errorCode={r.ErrorCode}");
        Console.WriteLine($"drawingBounds: ({r.DrawingBounds?.MinX},{r.DrawingBounds?.MinY}) ~ ({r.DrawingBounds?.MaxX},{r.DrawingBounds?.MaxY})");
        Console.WriteLine($"rooms: {r.Rooms.Count}");
        int i = 0;
        foreach (var room in r.Rooms)
            Console.WriteLine($"  #{i++}: {room.AreaM2,7:F2}㎡  {room.WidthMm}×{room.DepthMm}mm  label={room.Label} type={room.RoomType} group={room.SpaceGroup} conf={room.Confidence} check={room.DimensionCheck?.Annotated}/{(room.DimensionCheck?.Consistent == true ? "一致" : room.DimensionCheck?.Consistent == false ? "不一致" : "-")} 成员={string.Join("+", room.MemberLabels ?? new List<string>())}");
        Console.WriteLine($"unnamedRegions: {r.UnnamedRegions.Count}");
        foreach (var u in r.UnnamedRegions)
            Console.WriteLine($"  {u.AreaM2,7:F2}㎡  {u.WidthMm}×{u.DepthMm}mm"
                              + $"  point=({u.LabelPoint?.X:F0},{u.LabelPoint?.Y:F0}) hint={u.Hint}");
        Console.WriteLine("qualityIssues:");
        foreach (var q in r.QualityIssues) Console.WriteLine($"  [{q.Level}] {q.Code}: {q.Message}");

        Console.WriteLine("\n===== 面积标注匹配（±3%） =====");
        int matched = 0;
        foreach (var a in detailed.AreaAnnotations)
        {
            var best = r.Rooms.OrderBy(x => Math.Abs(x.AreaM2 - a.AreaM2)).FirstOrDefault();
            bool ok = best != null && Math.Abs(best.AreaM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            if (ok) matched++;
            Console.WriteLine($"  标注 {a.AreaM2,7:F2}㎡ [{a.Layer}] \"{a.RawText}\" → {(ok ? $"匹配房间 {best!.AreaM2:F2}㎡ ({Math.Abs(best.AreaM2 - a.AreaM2) / a.AreaM2:P1})" : $"未匹配（最近 {best?.AreaM2:F2}㎡）")}");
        }
        Console.WriteLine($"匹配率: {matched}/{detailed.AreaAnnotations.Count}");
    }
}
