using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>以含独立房间标签的闭合完成面替换栅格估计，不将家具轮廓解释为房间。</summary>
public static class FinishRoomRecovery
{
    /// <summary>恢复有明确闭合完成面的空间；冲突或无法安全扣除重叠时保留原结果。</summary>
    public static int Recover(List<AlignedSpace> spaces, IEnumerable<FlatEntity> entities,
        List<FlatEntity> texts, Regex labelPattern, ParserOptions options)
    {
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        var finishPattern = new Regex(options.InnerFaceLayerPattern);
        int recovered = 0;
        foreach (var entity in entities.Where(entity => entity.Kind == FlatKind.Polyline
                     && finishPattern.IsMatch(entity.Layer)))
        {
            if (entity.Points is not { Count: >= 3 } points) continue;
            if (!entity.Closed && points[0].Distance(points[^1]) > 1) continue;
            var coordinates = points.Select(point => point.Copy()).ToList();
            if (coordinates[0].Distance(coordinates[^1]) <= 1) coordinates[^1] = coordinates[0].Copy();
            else coordinates.Add(coordinates[0].Copy());
            if (coordinates.Count < 4) continue;
            var polygon = factory.CreatePolygon(coordinates.ToArray());
            if (!polygon.IsValid || polygon.Area / 1e6 < options.MinRoomAreaM2
                || polygon.Area / 1e6 > options.MaxRoomAreaM2) continue;
            var candidate = new AlignedSpace { Polygon = polygon };
            if (!RoomLabelMatcher.AssignLabel(candidate, texts, labelPattern)
                || candidate.MemberLabels.Count > 0) continue;
            var matching = spaces.Where(space => space.Label == candidate.Label).ToList();
            if (matching.Count > 1) continue;
            if (matching.Any(space => space.MemberLabels.Count > 0 || space.SpaceGroup != null
                || space.Polygon.Intersection(polygon).Area <= 1)) continue;
            var edits = new List<(AlignedSpace Space, Polygon Polygon)>();
            bool conflict = false;
            foreach (var space in spaces.Except(matching))
            {
                if (!space.Polygon.EnvelopeInternal.Intersects(polygon.EnvelopeInternal)
                    || space.Polygon.Intersection(polygon).Area <= 1) continue;
                var remainder = space.Polygon.Difference(polygon);
                if (remainder is not Polygon remaining || !remaining.IsValid
                    || remaining.Area / 1e6 < options.MinRoomAreaM2
                    || texts.Any(text => text.Text == space.Label && text.TextPos != null
                        && space.Polygon.Contains(factory.CreatePoint(text.TextPos))
                        && !remaining.Contains(factory.CreatePoint(text.TextPos))))
                {
                    conflict = true;
                    break;
                }
                edits.Add((space, remaining));
            }
            if (conflict) continue;
            foreach (var edit in edits) edit.Space.Polygon = edit.Polygon;
            foreach (var space in matching) spaces.Remove(space);
            spaces.Add(candidate);
            recovered++;
        }
        return recovered;
    }
}
