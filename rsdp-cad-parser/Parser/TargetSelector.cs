using System.Text.RegularExpressions;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 目标子图选择（P1 实测修正版）。
///
/// 背景：金样本中同一户型被绘制了 8+ 个副本（平面/拆墙/砌墙/铺贴/天花…），
/// 副本间距小且被跨图实体桥接，纯实体密度聚类无法把副本拆开（gap 2m~10m 都粘成 420m 大簇）。
/// 因此改为"房间名标签定位"：
///   1. 标签（客厅/卧室/卫生间…）按插入点坐标聚类（间距 > ClusterGapMm 断开）→ 每簇 = 一个户型副本；
///   2. 每簇区域 = 标签包围盒外扩 TargetRegionMarginMm；
///   3. 目标 = 区域内"面积: X㎡"标注最多的簇（铺贴图副本自带校验基准），并列时取标签种类最多者；
///   4. 无标签时兜底：取实体密度聚类的最大簇，区域 = 其包围盒。
/// </summary>
public static class TargetSelector
{
    public class TargetRegion
    {
        public Envelope Region { get; set; } = new();
        public int LabelCount { get; set; }
        public int AnnotationCount { get; set; }
        public bool FromLabels { get; set; }
    }

    public static TargetRegion Select(List<FlatEntity> entities, List<AreaAnnotation> annotations, ParserOptions opt, List<string>? diagnostics = null)
    {
        var labelRe = new Regex(opt.RoomLabelPattern);
        var labels = entities
            .Where(e => e.Kind == FlatKind.Text && e.Text != null && e.TextPos != null && labelRe.IsMatch(e.Text))
            .ToList();

        if (labels.Count == 0)
        {
            diagnostics?.Add("全图无房间名标签，兜底取最大实体簇");
            var clusters = SubDrawingClusterer.Split(entities, opt);
            var biggest = clusters.OrderByDescending(c => c.Entities.Count).First();
            return new TargetRegion { Region = biggest.BBox.Copy(), FromLabels = false };
        }

        // 标签点聚类（并查集，间距 > ClusterGapMm 断开）
        int n = labels.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        for (int i = 0; i < n; i++)
            for (int j = i + 1; j < n; j++)
                if (labels[i].TextPos!.Distance(labels[j].TextPos!) <= opt.ClusterGapMm)
                { int a = Find(i), b = Find(j); if (a != b) parent[a] = b; }

        var groups = labels.Select((e, i) => (e, root: Find(i)))
            .GroupBy(x => x.root)
            .Select(g => g.Select(x => x.e).ToList())
            .ToList();

        double margin = opt.TargetRegionMarginMm;
        var candidates = groups.Select(g =>
        {
            var bb = new Envelope();
            foreach (var e in g) bb.ExpandToInclude(e.TextPos!);
            var region = bb.Copy();
            region.ExpandBy(margin);
            int annCount = annotations.Count(a => region.Contains(a.Pos));
            int distinctLabels = g.Select(e => e.Text!).Distinct().Count();
            return new TargetRegion { Region = region, LabelCount = distinctLabels, AnnotationCount = annCount, FromLabels = true };
        }).ToList();

        var best = candidates
            .OrderByDescending(c => c.AnnotationCount)
            .ThenByDescending(c => c.LabelCount)
            .First();
        diagnostics?.Add($"户型副本 {candidates.Count} 个（按标签聚类），目标副本：标签 {best.LabelCount} 种、面积标注 {best.AnnotationCount} 处，"
                         + $"区域 ({best.Region.MinX / 1000:F1},{best.Region.MinY / 1000:F1})~({best.Region.MaxX / 1000:F1},{best.Region.MaxY / 1000:F1})");
        return best;
    }
}
