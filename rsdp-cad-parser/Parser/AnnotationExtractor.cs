using System.Text.RegularExpressions;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 图纸文字标注收集：房间名标签 + "面积: X㎡" 标注（金样本"地面铺贴图"图层有现成标注，
/// 作为 P1 多边形化质量的校验基准）。
/// </summary>
public static class AnnotationExtractor
{
    private static readonly Regex AreaRe = new(@"面积[:：]\s*(\d+(?:\.\d+)?)\s*(?:㎡|m2|平米)?", RegexOptions.Compiled);

    /// <summary>收集全部实体的"面积"标注（跨子图，因为标注常在独立的铺贴子图上）。</summary>
    public static List<AreaAnnotation> ExtractAreaAnnotations(List<FlatEntity> entities)
    {
        var result = new List<AreaAnnotation>();
        foreach (var e in entities)
        {
            if (e.Kind != FlatKind.Text || e.Text == null || e.TextPos == null) continue;
            foreach (Match m in AreaRe.Matches(e.Text))
            {
                if (double.TryParse(m.Groups[1].Value, out double area) && area > 0)
                    result.Add(new AreaAnnotation(area, e.TextPos, e.Layer, e.Text));
            }
        }
        return result;
    }

    /// <summary>标注面积去重（同一房间可能在多张子图上重复标注）：相同值（±0.01㎡）只留一个。</summary>
    public static List<AreaAnnotation> DistinctAreas(List<AreaAnnotation> annotations)
    {
        var result = new List<AreaAnnotation>();
        foreach (var a in annotations.OrderBy(x => x.AreaM2))
        {
            if (result.All(r => Math.Abs(r.AreaM2 - a.AreaM2) > 0.01))
                result.Add(a);
        }
        return result;
    }

    /// <summary>
    /// 面积标注 → 语义标注：为每处面积标注配对最近的空间名文字（≤2.5m，优先同图层；
    /// 铺贴图层的习惯是"房间名 / 面积: X㎡ / 周长: Xm"三行一组）。
    /// 名称含 、/及 的标记为合并空间标注（IsComposite）。
    /// </summary>
    public static List<SemanticAnnotation> PairWithNames(
        List<AreaAnnotation> annotations, List<FlatEntity> entities, string roomLabelPattern)
    {
        var labelRe = new Regex(roomLabelPattern);
        var nameTexts = entities
            .Where(e => e.Kind == FlatKind.Text && e.Text != null && e.TextPos != null
                        && e.Text.Length <= 20 && labelRe.IsMatch(e.Text))
            .ToList();
        var result = new List<SemanticAnnotation>();
        foreach (var a in annotations)
        {
            var best = nameTexts
                .Where(t => t.TextPos!.Distance(a.Pos) <= 2500)
                .OrderBy(t => t.Layer == a.Layer ? 0 : 1)
                .ThenBy(t => t.TextPos!.Distance(a.Pos))
                .FirstOrDefault();
            string name = best?.Text ?? "";
            bool composite = name.Contains('、') || name.Contains('及');
            result.Add(new SemanticAnnotation(name, a.AreaM2, a.Pos, a.Layer, composite));
        }
        return result;
    }
}
