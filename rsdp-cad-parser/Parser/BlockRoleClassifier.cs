using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>块实例角色（Scene-aware Block Fallback 核心判定）。</summary>
public enum BlockRole
{
    /// <summary>整图/方案级容器：匿名大块封装整户甚至多方案几何，不是单件家具。</summary>
    SCENE_CONTAINER,
    /// <summary>建筑构件：墙/柱/梁/门/窗等空间边界来源。</summary>
    ARCHITECTURE,
    /// <summary>家具实例（FurnitureClassifier 判定为家具）。</summary>
    FURNITURE,
    /// <summary>标注集合：块内图元以文字/尺寸为主（如整户房间名标签块）。</summary>
    ANNOTATION,
    /// <summary>证据不足。</summary>
    UNKNOWN
}

/// <summary>
/// 块角色分类（P1）：把 INSERT 实例树节点分到 SCENE_CONTAINER / ARCHITECTURE / FURNITURE /
/// ANNOTATION / UNKNOWN，供两条分支消费——建筑分支（空间围合几何来源）排除 FURNITURE/ANNOTATION
/// 节点的图元；家具分支剔除 SCENE_CONTAINER（下钻其子块找真家具）。
///
/// 判定顺序（先特殊后一般）：
///   1. ANNOTATION：图元 ≥3 且文字/尺寸占比 ≥ AnnotationTextRatio（如整户标签块 *U190）；
///   2. SCENE_CONTAINER：展开包围盒任一边 > MaxFurnitureSpanMm 且图元数 ≥ SceneContainerMinPrimitives；
///   3. ARCHITECTURE：块名/有效图层命中建筑词（墙/柱/梁/结构/门窗库/wall/column…）；
///   4. FURNITURE：FurnitureClassifier 未排除且（类别可分类或家具图层证据）；
///   5. UNKNOWN：其余。
/// </summary>
public static class BlockRoleClassifier
{
    private static readonly Regex ArchRe = new(
        @"(?i)(墙|柱|结构|承重|梁|wall|column|\$dorlib|\$winlib|^d门|^window$)",
        RegexOptions.Compiled);

    public class RoleVerdict
    {
        public BlockRole Role { get; set; } = BlockRole.UNKNOWN;
        public string Reason { get; set; } = "";
        /// <summary>展开图元包围盒最大边长（毫米），无几何为 0。</summary>
        public double MaxSpanMm { get; set; }
        public int PrimitiveCount { get; set; }
    }

    /// <summary>判定节点角色（统计含整棵子树的展开图元）。</summary>
    public static RoleVerdict Classify(BlockResolver.InsertNode node, ParserOptions opt)
    {
        var prims = node.AllPrimitives().ToList();
        var bb = new Envelope();
        foreach (var p in prims) Expand(bb, p);
        double maxSpan = bb.IsNull ? 0 : Math.Max(bb.MaxX - bb.MinX, bb.MaxY - bb.MinY);

        var verdict = new RoleVerdict { MaxSpanMm = maxSpan, PrimitiveCount = prims.Count };

        // 1. ANNOTATION：文字/尺寸为主（整户标签块、标注集合块）
        int textDim = prims.Count(p => p.Kind is FlatKind.Text or FlatKind.Dimension);
        if (prims.Count >= 3 && textDim >= prims.Count * opt.AnnotationTextRatio)
        {
            verdict.Role = BlockRole.ANNOTATION;
            verdict.Reason = "text_ratio";
            return verdict;
        }

        // 2. SCENE_CONTAINER：超大跨度 + 多图元
        if (maxSpan > opt.MaxFurnitureSpanMm && prims.Count >= opt.SceneContainerMinPrimitives)
        {
            verdict.Role = BlockRole.SCENE_CONTAINER;
            verdict.Reason = $"span {maxSpan / 1000:F1}m × {prims.Count} prims";
            return verdict;
        }

        // 3. ARCHITECTURE：块名/图层建筑语义
        if (ArchRe.IsMatch(node.BlockName ?? "") || ArchRe.IsMatch(node.Layer ?? ""))
        {
            verdict.Role = BlockRole.ARCHITECTURE;
            verdict.Reason = "arch_name_or_layer";
            return verdict;
        }

        // 4. FURNITURE：家具分类器判定
        var fv = FurnitureClassifier.Classify(node.BlockName ?? "", node.Attributes, node.Layer ?? "");
        if (!fv.Excluded && fv.Category is not "UNKNOWN")
        {
            verdict.Role = BlockRole.FURNITURE;
            verdict.Reason = fv.SemanticSource ?? "category";
            return verdict;
        }

        verdict.Role = BlockRole.UNKNOWN;
        verdict.Reason = fv.Excluded ? "non_furniture_excluded" : "no_evidence";
        return verdict;
    }

    private static void Expand(Envelope bb, FlatEntity e)
    {
        if (e.Points != null) foreach (var p in e.Points) bb.ExpandToInclude(p);
        if (e.Rings != null) foreach (var r in e.Rings) foreach (var p in r) bb.ExpandToInclude(p);
        if (e.TextPos != null) bb.ExpandToInclude(e.TextPos);
        if (e.Points == null && e.Rings == null && e.TextPos == null && !e.BBox.IsNull)
        {
            bb.ExpandToInclude(e.BBox.MinX, e.BBox.MinY);
            bb.ExpandToInclude(e.BBox.MaxX, e.BBox.MaxY);
        }
    }
}
