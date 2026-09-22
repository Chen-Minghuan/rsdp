using NetTopologySuite;
using System.Text.RegularExpressions;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 房间标签关联（设计文档 §3.2 ⑥）：
/// 标签点 → point-in-polygon；失败时按距离兜底（最近空间 ≤ 2m 记低置信）。
/// 房间类型映射到 room_type 字典码（主卧/次卧/小孩房等统一 BEDROOM，label 保留原文）。
/// </summary>
public static class RoomLabelMatcher
{
    /// <summary>空间名 → room_type 字典码（按 Key 长度降序匹配，保证"卫生间"优先于"卫"）。</summary>
    private static readonly (string Name, string Type)[] TypeMap =
    {
        ("客厅", "LIVING_ROOM"), ("起居室", "LIVING_ROOM"),
        ("餐厅", "DINING_ROOM"),
        ("厨房", "KITCHEN"),
        ("主卧", "BEDROOM"), ("次卧", "BEDROOM"), ("卧室", "BEDROOM"),
        ("小孩房", "BEDROOM"), ("儿童房", "BEDROOM"), ("客房", "BEDROOM"), ("工人房", "BEDROOM"),
        ("卫生间", "BATHROOM"), ("主卫", "BATHROOM"), ("公卫", "BATHROOM"), ("客卫", "BATHROOM"),
        ("次卫", "BATHROOM"), ("卫浴", "BATHROOM"), ("盥洗", "BATHROOM"), ("洗衣房", "BATHROOM"),
        ("阳台", "BALCONY"),
        ("书房", "STUDY"), ("茶室", "STUDY"),
        ("玄关", "HALLWAY"), ("过道", "HALLWAY"), ("走廊", "HALLWAY"), ("门厅", "HALLWAY"),
        ("衣帽间", "OTHER"), ("储物间", "OTHER"),
    };

    /// <summary>名称 → room_type 字典码；无法识别返回 OTHER。</summary>
    public static string MapRoomType(string label)
    {
        foreach (var (name, type) in TypeMap)
            if (label.Contains(name)) return type;
        return "OTHER";
    }

    /// <summary>判定文字是否为合并空间名（含 、/及，如"客餐厅、厨房及过道"）。</summary>
    public static bool IsCompositeName(string text) => text.Contains('、') || text.Contains('及');

    /// <summary>标签别名：合并空间名中的缩略写法 → 规范标签（如"客餐厅"涵盖"客厅"）。</summary>
    private static readonly Dictionary<string, string[]> LabelAliases = new()
    {
        ["客厅"] = new[] { "客餐" },
        ["卫生间"] = new[] { "卫" },
    };

    /// <summary>合并空间名是否涵盖某标签（含别名）。</summary>
    public static bool NameCovers(string compositeName, string label)
        => compositeName.Contains(label)
           || (LabelAliases.TryGetValue(label, out var aliases) && aliases.Any(compositeName.Contains));

    /// <summary>
    /// 为每个空间选择主标签：空间内的房间名标签（排除合并名）中距质心最近者；
    /// 其余标签进 MemberLabels。无标签返回 false。
    /// </summary>
    public static bool AssignLabel(AlignedSpace space, List<FlatEntity> texts, Regex labelRe)
    {
        var inside = texts
            .Where(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
                        && t.Text.Length <= 12 && labelRe.IsMatch(t.Text)
                        && !IsCompositeName(t.Text)
                        && space.Polygon.Contains(Nts.GeometryFactory.CreatePoint(t.TextPos)))
            .Select(t => t.Text!)
            .Distinct()
            .ToList();
        if (inside.Count == 0) return false;

        var centroid = space.Polygon.Centroid.Coordinate;
        double DistToCentroid(string t) => texts.Where(x => x.Text == t && x.TextPos != null
                    && space.Polygon.Contains(Nts.GeometryFactory.CreatePoint(x.TextPos)))
                    .Min(x => x.TextPos!.Distance(centroid));
        // 主标签选择：优先出现在关联标注名中的标签（含别名与"标注名⊂标签"的更具体写法，
        // 如"主卧"⊂"主卧及衣帽间"、"客厅"⊂"客餐厅"、标注"阳台"→标签"休闲阳台"），
        // 其中优先更具体（文本长）且能映射到具体房间类型（非 OTHER）者，再次距质心最近
        var inAnn = space.Annotation != null
            ? inside.Where(t => NameCovers(space.Annotation.Name, t) || t.Contains(space.Annotation.Name)).ToList()
            : new List<string>();
        space.Label = (inAnn.Count > 0 ? inAnn : inside)
            .OrderBy(t => MapRoomType(t) == "OTHER" ? 1 : 0)
            .ThenByDescending(t => t.Length)
            .ThenBy(DistToCentroid)
            .First();
        space.MemberLabels = inside.Where(t => t != space.Label).ToList();
        return true;
    }

    /// <summary>无标签空间的 hint：最近的房间名标签 + 距离。</summary>
    public static string? NearestLabelHint(AlignedSpace space, List<FlatEntity> texts, Regex labelRe)
    {
        var centroid = space.Polygon.Centroid.Coordinate;
        var best = texts
            .Where(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
                        && t.Text.Length <= 12 && labelRe.IsMatch(t.Text) && !IsCompositeName(t.Text))
            .OrderBy(t => t.TextPos!.Distance(centroid))
            .FirstOrDefault();
        if (best == null) return null;
        return $"相邻标签：{best.Text}（距离 {best.TextPos!.Distance(centroid):F0}mm）";
    }
}

/// <summary>NTS 几何工厂便捷别名。</summary>
internal static class Nts
{
    public static readonly GeometryFactory GeometryFactory = NtsGeometryServices.Instance.CreateGeometryFactory();
}
