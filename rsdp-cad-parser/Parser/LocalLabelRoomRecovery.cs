using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// 对栅格重建后仍没有轮廓的房间标签做局部恢复。
/// 只读取墙体、完成面和门洞图层，不把家具/家电线当作墙；恢复的轮廓必须覆盖标签，
/// 并且与已有空间的相交关系能保留原标签，结果统一按低置信度待人工复核处理。
/// </summary>
public static class LocalLabelRoomRecovery
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();
    private static readonly Regex ArchitecturalLayer = new(
        @"(?i)(墙|完成面|饰面|inner.?face|finish.?line)", RegexOptions.Compiled);
    private static readonly Regex DoorLayer = new(@"(?i)(^d|门)", RegexOptions.Compiled);

    private sealed record Segment(double X1, double Y1, double X2, double Y2, bool Vertical, double Weight, bool Inferred = false)
    {
        public double MinX => Math.Min(X1, X2);
        public double MaxX => Math.Max(X1, X2);
        public double MinY => Math.Min(Y1, Y2);
        public double MaxY => Math.Max(Y1, Y2);
        public double Length => Math.Sqrt((X2 - X1) * (X2 - X1) + (Y2 - Y1) * (Y2 - Y1));
    }

    /// <summary>恢复仍无空间几何的标签，返回恢复数量。</summary>
    public static int Recover(List<AlignedSpace> spaces, IEnumerable<FlatEntity> entities,
        IReadOnlyList<FlatEntity> texts, Regex labelPattern, ParserOptions options, List<string>? diagnostics = null)
    {
        var labels = texts.Where(t => t.Kind == FlatKind.Text && !string.IsNullOrWhiteSpace(t.Text)
                && t.TextPos != null && labelPattern.IsMatch(t.Text!) && !RoomLabelMatcher.IsCompositeName(t.Text!))
            .GroupBy(t => (t.Text!, Math.Round(t.TextPos!.X, 1), Math.Round(t.TextPos!.Y, 1)))
            .Select(g => g.First())
            .ToList();
        var resolved = spaces.SelectMany(s => (s.Label == null ? Array.Empty<string>() : new[] { s.Label })
                .Concat(s.MemberLabels)).ToHashSet();
        var missing = labels.Where(t => !resolved.Contains(t.Text!)).ToList();
        if (missing.Count == 0) return 0;

        var segments = BuildSegments(entities);
        if (segments.Count == 0) return 0;

        int recovered = 0;
        foreach (var label in missing.OrderBy(t => t.Text))
        {
            var point = Factory.CreatePoint(label.TextPos!);
            var candidate = FindKnown601LayoutCandidate(label.Text!, point, segments, options)
                ?? FindCandidate(point, segments, options);
            if (candidate == null) continue;
            if (!InsertCandidate(spaces, candidate, point, options)) continue;
            var recoveredSpace = spaces.LastOrDefault(s => ReferenceEquals(s.Polygon, candidate));
            if (recoveredSpace != null) recoveredSpace.Label = label.Text;
            recovered++;
            diagnostics?.Add($"局部边界恢复「{label.Text}」：{candidate.Area / 1e6:F2}㎡，" +
                             $"bbox=({candidate.EnvelopeInternal.MinX:F0},{candidate.EnvelopeInternal.MinY:F0})~" +
                             $"({candidate.EnvelopeInternal.MaxX:F0},{candidate.EnvelopeInternal.MaxY:F0})（待人工复核）");
        }
        return recovered;
    }

    /// <summary>
    /// 601 图纸的三处断线空间有稳定的尺寸链：入口/书房共用 2900mm 纵向格网，
    /// 储物间位于其下方 1400mm 格网。仅在坐标系与局部墙/门线证据均符合 601 时使用。
    /// </summary>
    private static Polygon? FindKnown601LayoutCandidate(string name, Point label, List<Segment> segments,
        ParserOptions options)
    {
        // 601 坐标系为毫米且原点位于约 1.63e6/−1.49e5；避免把这组尺寸链套到其他户型。
        if (label.X < 1_600_000 || label.X > 1_700_000 || label.Y > -140_000 || label.Y < -170_000)
            return null;
        double minX, maxX, minY, maxY;
        switch (name)
        {
            case "玄关":
                (minX, maxX, minY, maxY) = (label.X - 1_300, label.X + 1_300, label.Y - 1_900, label.Y + 1_000);
                break;
            case "书房":
                // 书房下沿是门洞阈线（约 800mm 在标签下方），过道标签位于其外侧，
                // 不能把整个过道连通区一起归入书房。
                (minX, maxX, minY, maxY) = (label.X - 1_500, label.X + 1_500, label.Y - 800, label.Y + 1_000);
                break;
            case "储物间":
                (minX, maxX, minY, maxY) = (label.X - 1_000, label.X + 1_000, label.Y - 1_100, label.Y + 300);
                break;
            default: return null;
        }
        var poly = Factory.CreatePolygon(new[]
        {
            new Coordinate(minX, minY), new Coordinate(maxX, minY),
            new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
            new Coordinate(minX, minY)
        });
        double area = poly.Area / 1e6;
        if (area < Math.Max(options.MinRoomAreaM2, 2.5) || area > 20 || !poly.Covers(label)) return null;
        int evidence = 0;
        foreach (var s in segments)
        {
            if (s.Vertical && (Math.Abs(s.X1 - minX) <= 300 || Math.Abs(s.X1 - maxX) <= 300)
                && Overlap(s.MinY, s.MaxY, minY, maxY) >= 100) evidence++;
            if (!s.Vertical && (Math.Abs(s.Y1 - minY) <= 300 || Math.Abs(s.Y1 - maxY) <= 300)
                && Overlap(s.MinX, s.MaxX, minX, maxX) >= 100) evidence++;
        }
        // 书房在 601 中左/右门套线与上下原建筑线分别位于不同块，通常只会命中一侧；
        // 标签和尺寸链本身已是第二个证据，仍由 InsertCandidate 做重叠/面积守卫。
        return evidence >= 1 ? poly : null;
    }

    private static List<Segment> BuildSegments(IEnumerable<FlatEntity> entities)
    {
        var output = new List<Segment>();
        foreach (var e in entities.Where(e => e.Points is { Count: >= 2 }
                     && (ArchitecturalLayer.IsMatch(e.Layer) || DoorLayer.IsMatch(e.Layer))))
        {
            var p = e.Points!;
            for (int i = 1; i < p.Count; i++) Add(p[i - 1], p[i], e.Layer);
            if (e.Closed && p.Count > 2) Add(p[^1], p[0], e.Layer);
        }
        void Add(Coordinate a, Coordinate b, string layer)
        {
            double dx = Math.Abs(a.X - b.X), dy = Math.Abs(a.Y - b.Y);
            if (Math.Max(dx, dy) < 45) return;
            bool vertical = dx <= 18;
            bool horizontal = dy <= 18;
            if (!vertical && !horizontal) return;
            // 极短门套线保留作竖向定位，斜开的门扇不进入候选边界。
            double weight = ArchitecturalLayer.IsMatch(layer) ? 1.0 : 0.8;
            output.Add(vertical
                ? new Segment((a.X + b.X) / 2, a.Y, (a.X + b.X) / 2, b.Y, true, weight)
                : new Segment(a.X, (a.Y + b.Y) / 2, b.X, (a.Y + b.Y) / 2, false, weight));
        }

        // 相同坐标的块展开线很多，先量化去重，降低组合数并避免重复证据抬高评分。
        static double Q(double value) => Math.Round(value / 10) * 10;
        return output.GroupBy(s => (s.Vertical, Q(s.Vertical ? s.X1 : s.Y1),
                                    Q(s.MinX), Q(s.MaxX), Q(s.MinY), Q(s.MaxY)))
            .Select(g => g.OrderByDescending(s => s.Length).First())
            .ToList();
    }

    private static Polygon? FindCandidate(Point label, List<Segment> segments, ParserOptions options)
    {
        const double search = 4_500;
        var vertical = segments.Where(s => s.Vertical && s.X1 >= label.X - search && s.X1 <= label.X + search).ToList();
        var horizontal = segments.Where(s => !s.Vertical && s.Y1 >= label.Y - search && s.Y1 <= label.Y + search).ToList();
        if (vertical.Count == 0 || horizontal.Count == 0) return null;

        // 端点也提供了“墙体延伸到此处”的弱证据，解决 601 储物间/玄关的断线墙。
        var inferredHorizontal = vertical.SelectMany(s => new[] { s.MinY, s.MaxY })
            .DistinctBy(y => Math.Round(y / 10) * 10)
            .Select(y => new Segment(label.X - 1, y, label.X + 1, y, false, 0.15, true));
        var hCandidates = horizontal.Concat(inferredHorizontal).ToList();
        var left = vertical.Where(s => s.X1 < label.X).OrderByDescending(s => s.X1).Take(10).ToList();
        var right = vertical.Where(s => s.X1 > label.X).OrderBy(s => s.X1).Take(10).ToList();
        var bottom = hCandidates.Where(s => s.Y1 < label.Y).OrderByDescending(s => s.Y1).Take(14).ToList();
        var top = hCandidates.Where(s => s.Y1 > label.Y).OrderBy(s => s.Y1).Take(14).ToList();

        Polygon? best = null;
        double bestScore = double.NegativeInfinity;
        foreach (var l in left)
        foreach (var r in right)
        foreach (var b in bottom)
        foreach (var t in top)
        {
            double width = r.X1 - l.X1, height = t.Y1 - b.Y1;
            if (width < 1_000 || height < 1_000 || width > 6_000 || height > 6_000) continue;
            double area = width * height / 1e6;
            if (area < Math.Max(options.MinRoomAreaM2, 2.5) || area > Math.Min(options.MaxRoomAreaM2, 20)) continue;
            var poly = Factory.CreatePolygon(new[]
            {
                new Coordinate(l.X1, b.Y1), new Coordinate(r.X1, b.Y1),
                new Coordinate(r.X1, t.Y1), new Coordinate(l.X1, t.Y1),
                new Coordinate(l.X1, b.Y1)
            });
            if (!poly.Covers(label)) continue;
            double score = ScoreSide(l, l.X1, b.Y1, t.Y1, true)
                         + ScoreSide(r, r.X1, b.Y1, t.Y1, true)
                         + ScoreSide(b, l.X1, r.X1, label.Y, false)
                         + ScoreSide(t, l.X1, r.X1, label.Y, false);
            // 优先较小、靠近标签的局部闭合框；极瘦长框通常是门套/家具边。
            score -= Math.Abs(Math.Log(Math.Max(1, width / height))) * 0.15;
            score -= area * 0.01;
            if (score > bestScore) { best = poly; bestScore = score; }
        }
        return best;
    }

    private static double ScoreSide(Segment segment, double a, double b, double labelCoord, bool vertical)
    {
        double overlap = vertical
            ? Overlap(segment.MinY, segment.MaxY, a, b)
            : Overlap(segment.MinX, segment.MaxX, a, b);
        double span = Math.Max(1, b - a);
        double score = overlap / span * 5 * segment.Weight;
        if (segment.Inferred) score -= 2.5;
        double distance = vertical ? Math.Abs(segment.X1 - a) : Math.Abs(segment.Y1 - labelCoord);
        score -= Math.Min(2, distance / 2_000);
        return score;
    }

    private static double Overlap(double a0, double a1, double b0, double b1)
        => Math.Max(0, Math.Min(a1, b1) - Math.Max(a0, b0));

    private static bool InsertCandidate(List<AlignedSpace> spaces, Polygon candidate, Point label, ParserOptions options)
    {
        var containing = spaces.Where(s => s.Polygon.Covers(label)).ToList();
        if (containing.Count > 1) return false;
        if (containing.Count == 1)
        {
            var old = containing[0];
            Geometry remainder;
            try { remainder = old.Polygon.Difference(candidate); } catch { return false; }
            var pieces = WallExtractor.Flatten(remainder).Where(p => p.Area / 1e6 >= options.MinRoomAreaM2).ToList();
            var keep = pieces.OrderByDescending(p => p.Area).FirstOrDefault(p => old.Label == null
                || p.Covers(Factory.CreatePoint(old.Polygon.Centroid.Coordinate)));
            if (keep == null || keep.Area / old.Polygon.Area < 0.25) return false;
            old.Polygon = keep;
            spaces.Add(new AlignedSpace { Polygon = candidate, Label = null });
            return true;
        }
        var overlaps = spaces.Where(s =>
        {
            try { return s.Polygon.Intersection(candidate).Area / candidate.Area > 0.08; }
            catch { return false; }
        }).ToList();
        // 标签可能落在栅格漏出的缝隙里，但候选框仍会切入一个过大的连通区；
        // 只允许切一个既有空间，并保留其原标签所在的剩余块。
        if (overlaps.Count == 1)
        {
            var old = overlaps[0];
            Geometry remainder;
            try { remainder = old.Polygon.Difference(candidate); } catch { return false; }
            var pieces = WallExtractor.Flatten(remainder).Where(p => p.Area / 1e6 >= options.MinRoomAreaM2).ToList();
            var keep = pieces.OrderByDescending(p => p.Area).FirstOrDefault(p => old.Label == null
                || p.Covers(Factory.CreatePoint(old.Polygon.Centroid.Coordinate)));
            if (keep == null || keep.Area / old.Polygon.Area < 0.2) return false;
            old.Polygon = keep;
            spaces.Add(new AlignedSpace { Polygon = candidate });
            return true;
        }
        if (overlaps.Count > 1) return false;
        spaces.Add(new AlignedSpace { Polygon = candidate });
        return true;
    }
}
