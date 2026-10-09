using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class OpenLivingDiningRefinerTests
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    [Fact]
    public void DiningBecomesRectangleAndLivingIncludesAllThreeChairs()
    {
        var spaces = Layout();
        Assert.True(OpenLivingDiningRefiner.Refine(spaces, Labels(), BasinLines(), new ParserOptions()));

        var dining = spaces.Single(space => space.Label == "餐厅").Polygon;
        var living = spaces.Single(space => space.Label == "客厅").Polygon;
        var hallway = spaces.Single(space => space.Label == "过道").Polygon;
        var washstand = spaces.Single(space => space.Label == "洗手台").Polygon;
        Assert.InRange(dining.Area / 1e6, 13.7, 13.9);
        Assert.InRange(living.Area / 1e6, 22.7, 22.9);
        Assert.InRange(hallway.Area / 1e6, 4.4, 4.7);
        Assert.InRange(washstand.Area / 1e6, 2.3, 2.4);
        Assert.True(washstand.Covers(Factory.CreatePoint(new Coordinate(3900, -900))));
        Assert.False(living.Covers(Factory.CreatePoint(new Coordinate(2800, -4000))));
        Assert.False(dining.Covers(Factory.CreatePoint(new Coordinate(4000, -6900))));

        var rooms = new List<(string RoomId, Polygon Geom)> { ("dining", dining), ("living", living) };
        foreach (double chairX in new[] { 4000.0, 5200.0, 6500.0 })
        {
            var anchor = new Coordinate(chairX, -6900);
            var footprint = Rectangle(chairX - 200, chairX + 200, -7100, -6700);
            Assert.Equal("living", RoomAssigner.Assign(anchor, footprint, rooms, new Envelope()).RoomId);
        }
    }

    [Fact]
    public void MissingBasinEvidenceDoesNotInventWashstandOrChangeRooms()
    {
        var spaces = Layout();
        var original = spaces.Select(space => space.Polygon).ToList();

        Assert.False(OpenLivingDiningRefiner.Refine(spaces, Labels(), Array.Empty<FlatEntity>(), new ParserOptions()));
        Assert.Equal(original.Count, spaces.Count);
        Assert.True(spaces.Select((space, index) => ReferenceEquals(space.Polygon, original[index])).All(equal => equal));
    }

    private static List<AlignedSpace> Layout()
    {
        var hallway = Factory.CreatePolygon(new[]
        {
            new Coordinate(0, 0), new Coordinate(3000, 0),
            new Coordinate(3000, -2330), new Coordinate(1210, -2330),
            new Coordinate(1210, -1215), new Coordinate(0, -1215), new Coordinate(0, 0)
        });
        var dining = Factory.CreatePolygon(new[]
        {
            new Coordinate(4410, -7700), new Coordinate(3680, -7700),
            new Coordinate(3680, -7600), new Coordinate(3190, -6900),
            new Coordinate(3480, -6900), new Coordinate(3480, -6200),
            new Coordinate(3080, -6200), new Coordinate(3080, -6000),
            new Coordinate(1380, -6000), new Coordinate(1380, -6140),
            new Coordinate(1210, -6140), new Coordinate(1210, -2330),
            new Coordinate(4410, -2330), new Coordinate(4410, -7700)
        });
        var living = Factory.CreatePolygon(new[]
        {
            new Coordinate(4410, -2330), new Coordinate(8000, -2330),
            new Coordinate(8000, -5000), new Coordinate(8400, -5200),
            new Coordinate(8400, -5400), new Coordinate(8000, -5400),
            new Coordinate(8000, -7700), new Coordinate(4410, -7700),
            new Coordinate(4410, -2330)
        });
        return new List<AlignedSpace>
        {
            new() { Label = "过道", Polygon = hallway },
            new() { Label = "餐厅", Polygon = dining },
            new() { Label = "客厅", Polygon = living },
            new() { Label = "公卫", Polygon = Rectangle(3100, 4740, -270, 1370) },
            new() { Label = "主卧", Polygon = Rectangle(8000, 11500, -5300, -1700) },
            new() { Label = "景观阳台", Polygon = Rectangle(8000, 11500, -8000, -5300) }
        };
    }

    private static List<FlatEntity> Labels() => new()
    {
        new() { Kind = FlatKind.Text, Text = "过道", TextPos = new Coordinate(1800, -900) },
        new() { Kind = FlatKind.Text, Text = "餐厅", TextPos = new Coordinate(2800, -4000) },
        new() { Kind = FlatKind.Text, Text = "客厅", TextPos = new Coordinate(6000, -4000) }
    };

    private static List<FlatEntity> BasinLines()
        => new[] { -700.0, -900.0, -1100.0 }.Select(y => new FlatEntity
        {
            Kind = FlatKind.Line,
            Layer = "J洁具（非落地）",
            Points = new() { new Coordinate(3400, y), new Coordinate(3700, y) },
            BBox = new Envelope(3400, 3700, y, y)
        }).ToList();

    private static Polygon Rectangle(double minX, double maxX, double minY, double maxY)
        => Factory.CreatePolygon(new[]
        {
            new Coordinate(minX, minY), new Coordinate(maxX, minY),
            new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
            new Coordinate(minX, minY)
        });
}
