using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class OpenPassageRefinerTests
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    [Fact]
    public void OpenHallwayStopsAtStudyAndDiningGridInsteadOfCrossingLivingRoom()
    {
        var spaces = Layout();
        var hallway = spaces.Single(space => space.Label == "过道");
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "过道", TextPos = new Coordinate(1800, -900) },
            new() { Kind = FlatKind.Text, Text = "餐厅", TextPos = new Coordinate(2800, -4000) }
        };

        Assert.True(OpenPassageRefiner.Refine(spaces, texts, new ParserOptions()));
        Assert.InRange(hallway.Polygon.Area / 1e6, 5.5, 5.7);
        Assert.Equal(3000, hallway.Polygon.EnvelopeInternal.MaxX);
        Assert.Equal(-2300, hallway.Polygon.EnvelopeInternal.MinY);
        Assert.False(hallway.Polygon.Covers(Factory.CreatePoint(new Coordinate(6500, -1900))));
        Assert.True(hallway.Polygon.Covers(Factory.CreatePoint(new Coordinate(2000, -1900))));
    }

    [Fact]
    public void UnverifiedNeighbourGridKeepsEstimatedHallway()
    {
        var spaces = Layout();
        spaces.Single(space => space.Label == "书房").Polygon = Rectangle(0, 3500, 0, 1800);
        var hallway = spaces.Single(space => space.Label == "过道");
        var original = hallway.Polygon;
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "过道", TextPos = new Coordinate(1800, -900) }
        };

        Assert.False(OpenPassageRefiner.Refine(spaces, texts, new ParserOptions()));
        Assert.Same(original, hallway.Polygon);
    }

    [Fact]
    public void AmbiguousHallwayLabelKeepsEstimatedHallway()
    {
        var spaces = Layout();
        var hallway = spaces.Single(space => space.Label == "过道");
        var original = hallway.Polygon;
        spaces.Add(new AlignedSpace { Label = "过道", Polygon = Rectangle(8000, 10000, 0, 2000) });
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "过道", TextPos = new Coordinate(1800, -900) }
        };

        Assert.False(OpenPassageRefiner.Refine(spaces, texts, new ParserOptions()));
        Assert.Same(original, hallway.Polygon);
    }

    private static List<AlignedSpace> Layout()
    {
        var hallway = Factory.CreatePolygon(new[]
        {
            new Coordinate(400, 0), new Coordinate(3000, 0),
            new Coordinate(3000, -1000), new Coordinate(7000, -1000),
            new Coordinate(7000, -2300), new Coordinate(400, -2300),
            new Coordinate(400, -1200), new Coordinate(400, 0)
        });
        return new List<AlignedSpace>
        {
            new() { Label = "过道", Polygon = hallway },
            new() { Label = "书房", Polygon = Rectangle(0, 3000, 0, 1800) },
            new() { Label = "玄关", Polygon = Rectangle(-2700, -100, -1100, 1800) },
            new() { Label = "储物间", Polygon = Rectangle(-1900, 100, -3000, -1600) },
            new() { Label = "餐厅", Polygon = Rectangle(1200, 4200, -6000, -2300) }
        };
    }

    private static Polygon Rectangle(double minX, double maxX, double minY, double maxY)
        => Factory.CreatePolygon(new[]
        {
            new Coordinate(minX, minY), new Coordinate(maxX, minY),
            new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
            new Coordinate(minX, minY)
        });
}
