using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 将栅格重建中包含多个可见房间名的连通区域，按标签位置生成待复核的语义分区。
/// 分界线是沿图纸坐标轴的估计线，不代表 CAD 中存在隔墙。
/// </summary>
public static class RasterLabelPartitioner
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>仅在所有标签都有有效分区且能覆盖原区域时拆分，否则保留原区域。</summary>
    public static List<AlignedSpace> Split(
        IReadOnlyList<AlignedSpace> spaces, IReadOnlyList<FlatEntity> texts,
        Regex labelPattern, double minAreaM2, out int splitCount)
    {
        splitCount = 0;
        var output = new List<AlignedSpace>();
        foreach (var space in spaces)
        {
            var labels = texts.Where(t => t.Kind == FlatKind.Text && t.Text != null && t.TextPos != null
                    && labelPattern.IsMatch(t.Text) && !RoomLabelMatcher.IsCompositeName(t.Text)
                    && space.Polygon.Contains(Factory.CreatePoint(t.TextPos)))
                .GroupBy(t => t.Text).Select(g => g.First()).ToList();
            if (labels.Count < 2) { output.Add(space); continue; }

            try
            {
                var cells = new Dictionary<FlatEntity, Envelope>();
                BuildCells(labels, space.Polygon.EnvelopeInternal, cells);
                var parts = new List<AlignedSpace>();
                foreach (var label in labels)
                {
                    var point = Factory.CreatePoint(label.TextPos!);
                    var clipped = space.Polygon.Intersection(Factory.ToGeometry(cells[label]));
                    var polygon = WallExtractor.Flatten(clipped).FirstOrDefault(p => p.Covers(point));
                    if (polygon == null || polygon.Area / 1e6 < minAreaM2) break;
                    parts.Add(new AlignedSpace { Polygon = polygon, Label = label.Text });
                }
                if (parts.Count == labels.Count)
                {
                    var union = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(
                        parts.Select(p => (Geometry)p.Polygon).ToList());
                    if (space.Polygon.SymmetricDifference(union).Area / space.Polygon.Area < 0.01)
                    {
                        output.AddRange(parts);
                        splitCount++;
                        continue;
                    }
                }
            }
            catch { /* 自交/退化图纸保留原区域，仍需人工校正 */ }
            output.Add(space);
        }
        return output;
    }

    private static void BuildCells(List<FlatEntity> labels, Envelope bounds,
        Dictionary<FlatEntity, Envelope> cells)
    {
        if (labels.Count == 1) { cells[labels[0]] = bounds; return; }
        bool splitX = true;
        double bestGap = double.NegativeInfinity;
        int splitAt = 1;
        List<FlatEntity>? ordered = null;
        foreach (bool onX in new[] { true, false })
        {
            var sorted = labels.OrderBy(t => onX ? t.TextPos!.X : t.TextPos!.Y).ToList();
            double span = Math.Max(1, onX ? bounds.Width : bounds.Height);
            for (int i = 1; i < sorted.Count; i++)
            {
                double left = onX ? sorted[i - 1].TextPos!.X : sorted[i - 1].TextPos!.Y;
                double right = onX ? sorted[i].TextPos!.X : sorted[i].TextPos!.Y;
                double gap = (right - left) / span;
                if (gap <= bestGap) continue;
                bestGap = gap;
                splitX = onX;
                splitAt = i;
                ordered = sorted;
            }
        }
        var cut = splitX
            ? (ordered![splitAt - 1].TextPos!.X + ordered[splitAt].TextPos!.X) / 2
            : (ordered![splitAt - 1].TextPos!.Y + ordered[splitAt].TextPos!.Y) / 2;
        var low = splitX
            ? new Envelope(bounds.MinX, cut, bounds.MinY, bounds.MaxY)
            : new Envelope(bounds.MinX, bounds.MaxX, bounds.MinY, cut);
        var high = splitX
            ? new Envelope(cut, bounds.MaxX, bounds.MinY, bounds.MaxY)
            : new Envelope(bounds.MinX, bounds.MaxX, cut, bounds.MaxY);
        BuildCells(ordered.Take(splitAt).ToList(), low, cells);
        BuildCells(ordered.Skip(splitAt).ToList(), high, cells);
    }
}
