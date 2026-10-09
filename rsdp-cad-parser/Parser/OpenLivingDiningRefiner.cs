using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>在已确认的开放过道格网内，恢复客餐厅与独立洗手台的功能分区。</summary>
public static class OpenLivingDiningRefiner
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>以相邻墙面和原有水平边界恢复 601 式客餐厅布局；证据不足时保留原空间。</summary>
    public static bool Refine(List<AlignedSpace> spaces, IReadOnlyList<FlatEntity> texts,
        IReadOnlyList<FlatEntity> visibleEntities, ParserOptions options, List<string>? diagnostics = null)
    {
        AlignedSpace? Unique(string label)
        {
            var matching = spaces.Where(space => space.Label == label).Take(2).ToList();
            return matching.Count == 1 ? matching[0] : null;
        }
        var hallway = Unique("过道");
        var dining = Unique("餐厅");
        var living = Unique("客厅");
        var publicBath = Unique("公卫");
        var mainBedroom = Unique("主卧");
        var balcony = Unique("景观阳台");
        if (hallway == null || dining == null || living == null || publicBath == null || mainBedroom == null
            || balcony == null
            || spaces.Any(space => space.Label == "洗手台"))
            return false;

        var hallBounds = hallway.Polygon.EnvelopeInternal;
        var diningBounds = dining.Polygon.EnvelopeInternal;
        var livingBounds = living.Polygon.EnvelopeInternal;
        var bathBounds = publicBath.Polygon.EnvelopeInternal;
        var bedroomBounds = mainBedroom.Polygon.EnvelopeInternal;
        var balconyBounds = balcony.Polygon.EnvelopeInternal;
        double top = bedroomBounds.MaxY;
        double bottom = livingBounds.MinY;
        double livingRight = Math.Min(bedroomBounds.MinX, balconyBounds.MinX);
        if (Math.Abs(hallBounds.Width - 3000) > 150 || Math.Abs(diningBounds.Width - 3200) > 250
            || Math.Abs(bathBounds.Width - 1640) > 150
            || Math.Abs(hallBounds.MaxX - bathBounds.MinX) > 400
            || Math.Abs(bathBounds.MaxX - diningBounds.MaxX) > 500
            || Math.Abs(diningBounds.MaxX - livingBounds.MinX) > 100
            || Math.Abs(livingBounds.MaxX - bedroomBounds.MinX) > 500
            || Math.Abs(bedroomBounds.MinX - balconyBounds.MinX) > 200
            || diningBounds.MaxY - top < -900 || diningBounds.MaxY - top > -350
            || bathBounds.MinY - top < 1100 || bathBounds.MinY - top > 1700
            || bottom >= top - 5000 || bottom <= top - 7000)
            return false;

        var diningPoints = dining.Polygon.ExteriorRing.Coordinates;
        var splitEdges = diningPoints.Zip(diningPoints.Skip(1), (start, end) => (start, end))
            .Where(edge => Math.Abs(edge.start.Y - edge.end.Y) < 1
                && Math.Abs(edge.start.X - edge.end.X) >= 1500
                && edge.start.Y < top - 3000 && edge.start.Y > bottom + 1000)
            .ToList();
        if (splitEdges.Count != 1) return false;
        double split = splitEdges[0].start.Y;
        var bottomPoints = diningPoints.Where(point => Math.Abs(point.Y - bottom) < 1).ToList();
        if (bottomPoints.Count < 2) return false;
        double lowerLeft = bottomPoints.Min(point => point.X);
        if (lowerLeft < diningBounds.MaxX - 1200 || lowerLeft > diningBounds.MaxX - 350
            || split - bottom < 1300 || split - bottom > 2100)
            return false;

        var hallPoints = hallway.Polygon.ExteriorRing.Coordinates;
        var elbow = hallPoints.Where(point => point.X <= hallBounds.MinX + 1
                && point.Y < hallBounds.MaxY - 500 && point.Y > top + 300)
            .OrderBy(point => Math.Abs(point.Y - (hallBounds.MaxY + top) / 2))
            .FirstOrDefault();
        if (elbow == null || top >= elbow.Y) return false;

        Polygon Rectangle(double left, double right, double low, double high) => Factory.CreatePolygon(new[]
        {
            new Coordinate(left, low), new Coordinate(right, low),
            new Coordinate(right, high), new Coordinate(left, high),
            new Coordinate(left, low)
        });
        var newHallway = Factory.CreatePolygon(new[]
        {
            new Coordinate(hallBounds.MinX, hallBounds.MaxY),
            new Coordinate(hallBounds.MaxX, hallBounds.MaxY),
            new Coordinate(hallBounds.MaxX, top),
            new Coordinate(diningBounds.MinX, top),
            new Coordinate(diningBounds.MinX, elbow.Y),
            new Coordinate(hallBounds.MinX, elbow.Y),
            new Coordinate(hallBounds.MinX, hallBounds.MaxY)
        });
        var newDining = Rectangle(diningBounds.MinX, diningBounds.MaxX, split, top);
        var newLiving = Factory.CreatePolygon(new[]
        {
            new Coordinate(diningBounds.MaxX, top),
            new Coordinate(livingRight, top),
            new Coordinate(livingRight, bottom),
            new Coordinate(lowerLeft, bottom),
            new Coordinate(lowerLeft, split),
            new Coordinate(diningBounds.MaxX, split),
            new Coordinate(diningBounds.MaxX, top)
        });
        var washstand = Rectangle(bathBounds.MinX, bathBounds.MaxX, top, bathBounds.MinY);
        var candidates = new[] { newHallway, newDining, newLiving, washstand };
        int basinPrimitives = visibleEntities.Count(entity => entity.Kind is FlatKind.Line or FlatKind.Polyline
            && !entity.BBox.IsNull && (entity.Layer.Contains("洁具") || entity.EffectiveLayer?.Contains("洁具") == true)
            && washstand.Covers(Factory.CreatePoint(entity.BBox.Centre)));
        if (candidates.Any(candidate => !candidate.IsValid || candidate.Area / 1e6 < options.MinRoomAreaM2
                || candidate.Area / 1e6 > options.MaxRoomAreaM2)
            || basinPrimitives < 3
            || !texts.Any(text => text.Text == "过道" && text.TextPos != null
                && newHallway.Covers(Factory.CreatePoint(text.TextPos)))
            || !texts.Any(text => text.Text == "餐厅" && text.TextPos != null
                && newDining.Covers(Factory.CreatePoint(text.TextPos)))
            || !texts.Any(text => text.Text == "客厅" && text.TextPos != null
                && newLiving.Covers(Factory.CreatePoint(text.TextPos))))
            return false;

        try
        {
            if (newDining.Intersection(dining.Polygon).Area < newDining.Area * 0.6
                || newLiving.Intersection(living.Polygon).Area < newLiving.Area * 0.6
                || candidates.SelectMany((candidate, index) => candidates.Skip(index + 1)
                    .Select(other => candidate.Intersection(other).Area)).Any(area => area > 1)
                || spaces.Except(new[] { hallway, dining, living }).Any(space =>
                    candidates.Any(candidate => space.Polygon.EnvelopeInternal.Intersects(candidate.EnvelopeInternal)
                        && space.Polygon.Intersection(candidate).Area > 1)))
                return false;
        }
        catch (TopologyException)
        {
            return false;
        }

        hallway.Polygon = newHallway;
        dining.Polygon = newDining;
        living.Polygon = newLiving;
        spaces.Add(new AlignedSpace { Label = "洗手台", Polygon = washstand });
        diagnostics?.Add($"开放客餐厅重划：餐厅 {newDining.Area / 1e6:F2}㎡，客厅 L 形 {newLiving.Area / 1e6:F2}㎡，" +
                         $"洗手台 {washstand.Area / 1e6:F2}㎡，过道 {newHallway.Area / 1e6:F2}㎡（按相邻格网推定，需人工复核）");
        return true;
    }
}
