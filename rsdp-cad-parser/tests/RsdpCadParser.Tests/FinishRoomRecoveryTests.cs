using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class FinishRoomRecoveryTests
{
    [Theory]
    [InlineData(true, "W装饰完成面", 2)]
    [InlineData(false, "W装饰完成面", 2)]
    [InlineData(true, "J洁具(落地)", 0)]
    public void ClosedFinishReplacesEstimateWithoutOverlappingNeighbour(bool closed, string layer, int expected)
    {
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        Polygon Rectangle(double left, double right) => factory.CreatePolygon(new[]
        {
            new Coordinate(left, 0), new(right, 0), new(right, 2000), new(left, 2000), new(left, 0)
        });
        var spaces = new List<AlignedSpace>
        {
            new() { Polygon = Rectangle(0, 1500), Label = "公卫" },
            new() { Polygon = Rectangle(1500, 5000), Label = "次卧" }
        };
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "公卫", TextPos = new(1000, 1000) },
            new() { Kind = FlatKind.Text, Text = "次卧", TextPos = new(3500, 1000) }
        };
        var finish = new FlatEntity
        {
            Kind = FlatKind.Polyline, Layer = layer, Closed = closed,
            Points = Rectangle(0, 2000).ExteriorRing.Coordinates.ToList()
        };
        int count = FinishRoomRecovery.Recover(spaces, new[] { finish }, texts,
            new Regex("公卫|次卧"), new ParserOptions());
        Assert.Equal(expected > 0 ? 1 : 0, count);
        Assert.Equal(expected > 0 ? 4 : 3, spaces.Single(space => space.Label == "公卫").Polygon.Area / 1e6);
        Assert.True(spaces[0].Polygon.Intersection(spaces[1].Polygon).Area < 1);
    }
}
