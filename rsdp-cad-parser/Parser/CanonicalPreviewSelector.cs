using System.Text.RegularExpressions;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>完整户型线稿底图：保留可见建筑、家具、洁具与家电，去除标注及机电噪声。</summary>
public static class CanonicalPreviewSelector
{
    private static readonly Regex NoiseLayer = new(
        @"(?i)(文字|标注|尺寸|机电|消防|烟感|喷淋|摄像|探测器|强弱电|defpoints|^dim)", RegexOptions.Compiled);

    /// <summary>选择已按 CAD 可见性展开的图元；不恢复动态块隐藏状态，不改变识别事实。</summary>
    public static List<FlatEntity> Select(
        IReadOnlyList<FlatEntity> directEntities,
        IReadOnlyList<BlockResolver.InsertNode> roots,
        ParserOptions options)
    {
        bool Keep(FlatEntity e) => e.Kind is FlatKind.Line or FlatKind.Polyline or FlatKind.Hatch
            && !NoiseLayer.IsMatch(e.Layer);
        var selected = directEntities.Where(Keep).ToList();
        void Walk(BlockResolver.InsertNode node)
        {
            if (BlockRoleClassifier.Classify(node, options).Role == BlockRole.ANNOTATION) return;
            if (NoiseLayer.IsMatch(node.Layer)) return;
            selected.AddRange(node.Primitives.Where(Keep));
            foreach (var child in node.Children) Walk(child);
        }
        foreach (var root in roots) Walk(root);
        return selected.Distinct().ToList();
    }
}
