using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class LocalLabelRoomRecoveryTests
{
    [Fact]
    public void RecoversMissingLabelFromArchitecturalAndDoorLines()
    {
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        var spaces = new List<AlignedSpace>();
        var entities = new List<FlatEntity>
        {
            Line("A原建筑墙体", 0, 0, 3000, 0),
            Line("A原建筑墙体", 0, 1800, 3000, 1800),
            Line("A原建筑墙体", 0, 0, 0, 1800),
            Line("A原建筑墙体", 3000, 0, 3000, 1800),
            new() { Kind = FlatKind.Text, Text = "书房", TextPos = new Coordinate(1500, 900) }
        };

        var count = LocalLabelRoomRecovery.Recover(spaces, entities, entities,
            new Regex("书房"), new ParserOptions());

        Assert.Equal(1, count);
        var room = Assert.Single(spaces);
        Assert.Equal("书房", room.Label);
        Assert.InRange(room.Polygon.Area / 1e6, 5.0, 6.0);
    }

    [Fact]
    public void DoesNotUseFurnitureLinesAsRoomBoundaries()
    {
        var spaces = new List<AlignedSpace>();
        var entities = new List<FlatEntity>
        {
            Line("J固定家具(落地)", 0, 0, 3000, 0),
            Line("J固定家具(落地)", 0, 1800, 3000, 1800),
            Line("J固定家具(落地)", 0, 0, 0, 1800),
            Line("J固定家具(落地)", 3000, 0, 3000, 1800),
            new() { Kind = FlatKind.Text, Text = "缺失房间", TextPos = new Coordinate(1500, 900) }
        };

        Assert.Equal(0, LocalLabelRoomRecovery.Recover(spaces, entities, entities,
            new Regex("缺失房间"), new ParserOptions()));
    }

    private static FlatEntity Line(string layer, double x1, double y1, double x2, double y2)
        => new()
        {
            Kind = FlatKind.Line,
            Layer = layer,
            Points = new() { new Coordinate(x1, y1), new Coordinate(x2, y2) },
            BBox = new Envelope(Math.Min(x1, x2), Math.Max(x1, x2), Math.Min(y1, y2), Math.Max(y1, y2))
        };
}
