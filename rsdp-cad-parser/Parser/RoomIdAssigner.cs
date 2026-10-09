using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 房间稳定 ID 分配：按 labelPoint（X→Y）排序确定性编号 ROOM-001…，
/// 同一解析结果内可重现，家具 roomId 引用之。
/// </summary>
public static class RoomIdAssigner
{
    public static void Assign(List<RoomDto> rooms)
        => Assign(rooms, null);

    /// <summary>命名房间优先编号，未命名空间续编同一序列（P1：未命名空间参与家具归属）。</summary>
    public static void Assign(List<RoomDto> rooms, List<UnnamedRegionDto>? unnamed)
    {
        int seq = 1;
        foreach (var r in rooms
                     .OrderBy(r => r.LabelPoint?.X ?? double.MaxValue)
                     .ThenBy(r => r.LabelPoint?.Y ?? double.MaxValue)
                     .ThenByDescending(r => r.AreaM2))
            r.RoomId = $"ROOM-{seq++:D3}";
        if (unnamed == null) return;
        foreach (var u in unnamed
                     .OrderBy(u => u.LabelPoint?.X ?? double.MaxValue)
                     .ThenBy(u => u.LabelPoint?.Y ?? double.MaxValue)
                     .ThenByDescending(u => u.AreaM2))
            u.RoomId = $"ROOM-{seq++:D3}";
    }
}
