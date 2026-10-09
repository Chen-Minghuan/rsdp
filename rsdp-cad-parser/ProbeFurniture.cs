using System.Text.Json;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>
/// 家具识别端到端摸底：完整解析后输出家具实例数、类别/UNKNOWN 分布、
/// 目标场景候选数、parseStatus、单位结论、不支持实体摘要。
/// </summary>
public static class ProbeFurniture
{
    public static void Run(string file)
    {
        var detailed = CadParsePipeline.ParseDetailed(File.ReadAllBytes(file), Path.GetFileName(file));
        var r = detailed.Result;

        Console.WriteLine("== 单位结论 ==");
        if (r.UnitResolution != null)
            Console.WriteLine($"rawUnit={r.UnitResolution.RawUnit} declaredUnit={r.UnitResolution.DeclaredUnit ?? "<null>"} "
                              + $"scaleToMm={r.UnitResolution.ScaleToMm} scaleSource={r.UnitResolution.ScaleSource} "
                              + $"scaleAssumed={r.UnitResolution.ScaleAssumed} confirmed={r.UnitResolution.Confirmed} confidence={r.UnitResolution.Confidence}");

        Console.WriteLine("== 状态 ==");
        Console.WriteLine($"success={r.Success} parseStatus={r.ParseStatus}");
        if (r.Capabilities != null)
            Console.WriteLine($"capabilities={JsonSerializer.Serialize(r.Capabilities)}");
        Console.WriteLine($"sceneVersion={r.SceneVersion} parserVersion={r.ParserVersion}");

        Console.WriteLine("== 目标场景 ==");
        if (r.TargetScene != null)
            Console.WriteLine($"targetScene={r.TargetScene.TargetId} source={r.TargetScene.SelectionSource} confidence={r.TargetScene.SelectionConfidence} "
                              + $"bounds=({r.TargetScene.Bounds?.MinX:F0},{r.TargetScene.Bounds?.MinY:F0})~({r.TargetScene.Bounds?.MaxX:F0},{r.TargetScene.Bounds?.MaxY:F0}) "
                              + $"reasons=[{string.Join(",", r.TargetScene.Reasons)}]");
        Console.WriteLine($"targetSceneCandidates={r.TargetSceneCandidates?.Count ?? 0} 个：");
        foreach (var c in r.TargetSceneCandidates ?? new())
            Console.WriteLine($"  {c.TargetId} score={c.Score} recommended={c.Recommended} "
                              + $"bounds=({c.Bounds?.MinX:F0},{c.Bounds?.MinY:F0})~({c.Bounds?.MaxX:F0},{c.Bounds?.MaxY:F0}) reasons=[{string.Join(",", c.Reasons)}]");

        Console.WriteLine("== 家具 ==");
        var furn = r.Furniture ?? new();
        Console.WriteLine($"家具实例数={furn.Count}");
        foreach (var g in furn.GroupBy(f => f.Category).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  {g.Key}: {g.Count()}");
        foreach (var f in furn)
            Console.WriteLine($"  {f.FurnitureId} {f.Category,-7} conf={f.CategoryConfidence} block={f.BlockName ?? "-"} label={f.SemanticLabel ?? "-"} "
                              + $"room={f.RoomId ?? "-"} pos=({f.Position?.X:F0},{f.Position?.Y:F0}) rot={f.RotationDeg} "
                              + $"geo={f.GeometryWidthMm}x{f.GeometryDepthMm} depth={f.InsertPath.Count} "
                              + $"evidence=[{string.Join(",", f.Evidence)}] flags=[{string.Join(",", f.QualityFlags)}]");

        Console.WriteLine("== 房间 ==");
        Console.WriteLine($"rooms={r.Rooms.Count} unnamed={r.UnnamedRegions.Count}");
        foreach (var room in r.Rooms)
            Console.WriteLine($"  {room.RoomId} {room.Label} {room.AreaM2}㎡");

        if (r.UnsupportedEntitySummary != null)
            Console.WriteLine($"unsupportedEntitySummary={JsonSerializer.Serialize(r.UnsupportedEntitySummary)}");
        Console.WriteLine($"qualityIssues={r.QualityIssues.Count}：");
        foreach (var q in r.QualityIssues)
            Console.WriteLine($"  [{q.Level}] {q.Code}: {q.Message}");
    }
}
