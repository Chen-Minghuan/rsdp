using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// DIMENSION 交叉验证（设计文档 §3.2 ⑦）：
/// 收集目标子图的 DIMENSION 实体 measurement，按文字中点位置关联到房间；
/// "家具尺寸"图层的标注为房间净尺寸，优先利用。
/// 几何值与标注值偏差 >2% 记 DIM_MISMATCH，几何值为准。
/// </summary>
public static class DimensionExtractor
{
    /// <summary>
    /// 为房间找尺寸标注：房间内的 DIMENSION（优先"家具尺寸"图层），
    /// 分别匹配开间（widthMm）与进深（depthMm）。返回 (annotated, consistent) 或 null。
    /// 仅对接近矩形的房间（面积/bbox面积 > 0.8）比对——L 形等异形空间的 bbox 长短轴无意义。
    /// </summary>
    public static (string Annotated, bool Consistent)? CheckDimensions(
        Polygon room, double widthMm, double depthMm,
        List<FlatEntity> dimensions, ParserOptions opt, List<QualityIssue> issues, string roomDesc)
    {
        double rectangularity = room.Area / (room.EnvelopeInternal.Area > 0 ? room.EnvelopeInternal.Area : 1);
        if (rectangularity < 0.8) return null;

        var pt = RoomLabelMatcherNts.GeometryFactory;
        // 净尺寸来源（"家具尺寸"图层）优先且唯一参与 MISmatch 判定；
        // 其他图层标注只用于正向匹配（避免家具/墙段尺寸误报）
        var inside = dimensions
            .Where(d => d.Measure is > 100 && d.TextPos != null
                        && room.Distance(pt.CreatePoint(d.TextPos)) < 300)
            .ToList();
        if (inside.Count == 0) return null;
        var trusted = inside.Where(d => d.Layer.Contains("家具尺寸")).ToList();

        double? wDim = FindNear(inside, widthMm, 0.02, out bool wOk);
        double? dDim = FindNear(inside, depthMm, 0.02, out bool dOk);

        if (wDim == null && dDim == null)
        {
            if (trusted.Count == 0) return null; // 无可信标注源 → 不做判定
            var nearest = trusted.OrderBy(d => Math.Min(
                Math.Abs(d.Measure!.Value - widthMm), Math.Abs(d.Measure.Value - depthMm))).First();
            double refV = Math.Abs(nearest.Measure!.Value - widthMm) < Math.Abs(nearest.Measure.Value - depthMm) ? widthMm : depthMm;
            double err = Math.Abs(nearest.Measure.Value - refV) / refV;
            // 偏差 >20% 的标注与房间开间/进深无关（家具/局部尺寸），不做判定
            if (err > 0.20) return null;
            issues.Add(new QualityIssue
            {
                Level = "warn",
                Code = "DIM_MISMATCH",
                Message = $"{roomDesc}：标注 {nearest.Measure.Value:F0} 与几何 {refV:F0} 偏差 {err:P1}（几何值为准）"
            });
            return ($"{nearest.Measure.Value:F0}", false);
        }
        // 单轴命中即视为一致（开间或进深之一被标注证实）
        string annotated = wDim != null && dDim != null
            ? $"{wDim:F0}×{dDim:F0}"
            : $"{(wDim ?? dDim):F0}";
        return (annotated, true);
    }

    /// <summary>在标注中找与 target 偏差 ≤tol 的最近测量值。</summary>
    private static double? FindNear(List<FlatEntity> dims, double target, double tol, out bool ok)
    {
        var best = dims.OrderBy(d => Math.Abs(d.Measure!.Value - target)).First();
        double err = Math.Abs(best.Measure!.Value - target) / target;
        ok = err <= tol;
        return ok ? best.Measure!.Value : null;
    }
}

/// <summary>内部几何工厂（避免循环引用）。</summary>
internal static class RoomLabelMatcherNts
{
    public static readonly GeometryFactory GeometryFactory = NtsGeometryServices.Instance.CreateGeometryFactory();
}
