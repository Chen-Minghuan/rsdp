using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 房间产出对比探针：打印 parseStatus、命名/未命名房间面积与顶点数、总面积、质量门码。
/// 第二参数 on/off 控制 WallSnapEnabled，用于内侧面吸附重构的前后对比（同输入同输出校验）。
/// </summary>
public static class ProbeRooms
{
    public static void Run(string path, bool snapEnabled)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var opt = new ParserOptions { WallSnapEnabled = snapEnabled };
        var detailed = CadParsePipeline.ParseDetailed(File.ReadAllBytes(path), Path.GetFileName(path), opt);
        var r = detailed.Result;
        Console.WriteLine($"文件: {path}");
        Console.WriteLine($"wallSnap={(snapEnabled ? "on" : "off")}  parseStatus={r.ParseStatus}  success={r.Success}");
        Console.WriteLine($"命名房间 {r.Rooms.Count}，未命名空间 {r.UnnamedRegions.Count}");
        double named = 0, unnamed = 0;
        foreach (var room in r.Rooms.OrderBy(x => x.Label).ThenByDescending(x => x.AreaM2))
        {
            named += room.AreaM2;
            Console.WriteLine($"  {room.Label,-12} {room.AreaM2,7:F2}㎡  顶点{room.Polygon.Length,3}  conf={room.Confidence}"
                              + (room.MemberLabels is { Count: > 0 } m ? $"  成员={string.Join("+", m)}" : ""));
        }
        foreach (var u in r.UnnamedRegions.OrderByDescending(x => x.AreaM2))
        {
            unnamed += u.AreaM2;
            Console.WriteLine($"  [未命名]     {u.AreaM2,7:F2}㎡  顶点{u.Polygon.Length,3}  hint={u.Hint}");
        }
        Console.WriteLine($"命名总面积 {named:F2}㎡，未命名总面积 {unnamed:F2}㎡，合计 {named + unnamed:F2}㎡");
        Console.WriteLine("质量门: " + string.Join(", ", r.QualityIssues.Select(i => $"{i.Level}:{i.Code}")));
        foreach (var d in detailed.Diagnostics.Where(x => x.Contains("内侧面"))) Console.WriteLine("[diag] " + d);
    }
}
