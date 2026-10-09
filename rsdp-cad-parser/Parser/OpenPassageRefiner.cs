using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>用相邻房间的格网收窄开放式过道，不将客餐厅的开放区域归入过道。</summary>
public static class OpenPassageRefiner
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>仅在书房、玄关、储物间和餐厅形成 601 式相邻尺寸链时修正过道，返回是否修正。</summary>
    public static bool Refine(List<AlignedSpace> spaces, IReadOnlyList<FlatEntity> texts,
        ParserOptions options, List<string>? diagnostics = null)
    {
        AlignedSpace? Unique(string label)
        {
            var matching = spaces.Where(space => space.Label == label).Take(2).ToList();
            return matching.Count == 1 ? matching[0] : null;
        }
        var hallway = Unique("过道");
        var study = Unique("书房");
        var entrance = Unique("玄关");
        var storage = Unique("储物间");
        var dining = Unique("餐厅");
        if (hallway == null || study == null || entrance == null || storage == null || dining == null)
            return false;

        var studyBounds = study.Polygon.EnvelopeInternal;
        var entranceBounds = entrance.Polygon.EnvelopeInternal;
        var storageBounds = storage.Polygon.EnvelopeInternal;
        var diningBounds = dining.Polygon.EnvelopeInternal;
        var hallwayBounds = hallway.Polygon.EnvelopeInternal;
        if (Math.Abs(studyBounds.Width - 3000) > 150 || Math.Abs(studyBounds.Height - 1800) > 150
            || Math.Abs(storageBounds.Width - 2000) > 150 || Math.Abs(storageBounds.Height - 1400) > 150
            || Math.Abs(studyBounds.MinX - entranceBounds.MaxX) > 450
            || Math.Abs(studyBounds.MinX - storageBounds.MaxX) > 450
            || diningBounds.MinX <= studyBounds.MinX + 600
            || diningBounds.MinX >= studyBounds.MaxX - 600
            || hallwayBounds.MaxX < studyBounds.MaxX + 2500
            || hallwayBounds.MinX > studyBounds.MinX + 600)
            return false;

        double top = studyBounds.MinY;
        double bottom = diningBounds.MaxY;
        if (top - bottom < 1900 || top - bottom > 2700) return false;
        var elbow = hallway.Polygon.ExteriorRing.Coordinates
            .Where(point => point.X <= hallwayBounds.MinX + 150
                && point.Y < top - 500 && point.Y > bottom + 500)
            .OrderBy(point => Math.Abs(point.Y - (top + bottom) / 2))
            .FirstOrDefault();
        if (elbow == null) return false;

        var candidate = Factory.CreatePolygon(new[]
        {
            new Coordinate(studyBounds.MinX, top),
            new Coordinate(studyBounds.MaxX, top),
            new Coordinate(studyBounds.MaxX, bottom),
            new Coordinate(diningBounds.MinX, bottom),
            new Coordinate(diningBounds.MinX, elbow.Y),
            new Coordinate(studyBounds.MinX, elbow.Y),
            new Coordinate(studyBounds.MinX, top)
        });
        if (!candidate.IsValid || candidate.Area / 1e6 < options.MinRoomAreaM2
            || candidate.Area >= hallway.Polygon.Area * 0.8)
            return false;

        var hallwayLabel = texts.FirstOrDefault(text => text.Text == "过道" && text.TextPos != null
            && candidate.Covers(Factory.CreatePoint(text.TextPos)));
        if (hallwayLabel == null || texts.Any(text => text.Text != null && text.Text != "过道"
            && text.TextPos != null && candidate.Contains(Factory.CreatePoint(text.TextPos))
            && spaces.Any(space => space.Label == text.Text)))
            return false;
        try
        {
            if (candidate.Intersection(hallway.Polygon).Area < candidate.Area * 0.75
                || spaces.Any(space => !ReferenceEquals(space, hallway)
                    && space.Polygon.EnvelopeInternal.Intersects(candidate.EnvelopeInternal)
                    && space.Polygon.Intersection(candidate).Area > 1))
                return false;
        }
        catch (TopologyException)
        {
            return false;
        }

        hallway.Polygon = candidate;
        diagnostics?.Add($"开放过道按书房/餐厅格网收窄：{candidate.Area / 1e6:F2}㎡（原 {hallwayBounds.Width:F0}mm 跨至客厅的估计边界已移除，需人工复核）");
        return true;
    }
}
