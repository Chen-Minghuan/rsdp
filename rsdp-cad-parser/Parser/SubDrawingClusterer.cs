using System.Text.RegularExpressions;
using NetTopologySuite.Geometries;
using NetTopologySuite.Index.Strtree;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 子图聚类拆分：按实体包围盒间距做连通聚类（间距 > ClusterGapMm 断开成簇），
/// 解决一个文件多张子图混排（金样本全图跨度 463m，含 5+ 张子图）。
/// 目标子图 = 房间名标签命中密度最高的簇。
/// </summary>
public static class SubDrawingClusterer
{
    public class Cluster
    {
        public int Index { get; set; }
        public List<FlatEntity> Entities { get; } = new();
        public Envelope BBox { get; } = new();
        public int LabelHits { get; set; }
        public double SpanXm => BBox.MaxX - BBox.MinX;
        public double SpanYm => BBox.MaxY - BBox.MinY;
    }

    public static List<Cluster> Split(List<FlatEntity> entities, ParserOptions opt)
    {
        int n = entities.Count;
        var parent = Enumerable.Range(0, n).ToArray();
        int Find(int x) { while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x]; } return x; }
        void Union(int a, int b) { a = Find(a); b = Find(b); if (a != b) parent[a] = b; }

        var tree = new STRtree<FlatEntity>();
        var index = new Dictionary<FlatEntity, int>(n);
        for (int i = 0; i < n; i++)
        {
            index[entities[i]] = i;
            tree.Insert(entities[i].BBox, entities[i]);
        }
        tree.Build();

        double gap = opt.ClusterGapMm;
        for (int i = 0; i < n; i++)
        {
            var expanded = entities[i].BBox.Copy();
            expanded.ExpandBy(gap);
            foreach (var hit in tree.Query(expanded))
            {
                if (index.TryGetValue(hit, out int j) && j != i)
                {
                    // 实际包围盒间距（非仅扩张相交的粗判）
                    if (entities[i].BBox.Distance(hit.BBox) <= gap)
                        Union(i, j);
                }
            }
        }

        var labelRe = new Regex(opt.RoomLabelPattern);
        var groups = new Dictionary<int, Cluster>();
        for (int i = 0; i < n; i++)
        {
            int root = Find(i);
            if (!groups.TryGetValue(root, out var c))
            {
                c = new Cluster { Index = groups.Count };
                groups[root] = c;
            }
            var e = entities[i];
            c.Entities.Add(e);
            c.BBox.ExpandToInclude(e.BBox);
            if (e.Kind == FlatKind.Text && e.Text != null && labelRe.IsMatch(e.Text))
                c.LabelHits++;
        }
        return groups.Values.OrderByDescending(c => c.Entities.Count).ToList();
    }

    /// <summary>选择目标子图：房间名标签命中最多；并列时取实体数最多；全无标签时取最大簇。</summary>
    public static (Cluster Target, bool HasLabels) SelectTarget(List<Cluster> clusters)
    {
        var withLabels = clusters.Where(c => c.LabelHits > 0).ToList();
        if (withLabels.Count == 0)
            return (clusters.OrderByDescending(c => c.Entities.Count).First(), false);
        return (withLabels
            .OrderByDescending(c => c.LabelHits)
            .ThenByDescending(c => c.Entities.Count)
            .First(), true);
    }
}
