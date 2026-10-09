using System.Text.RegularExpressions;
using ACadSharp.Entities;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class RoomRecoveryTests
{
    [Fact]
    public void InvisibleCadTextDoesNotEnterTheScene()
    {
        var hidden = new MText { IsInvisible = true, Value = "主卧" };
        Assert.Null(EntityExtractor.Convert(hidden, 1));
        Assert.Equal("主卧", MTextCleaner.Clean("主 卧"));
    }

    [Fact]
    public void RasterRoomIsFilteredAfterRegrowth()
    {
        var corners = new List<Coordinate>
        {
            new(0, 0), new(2000, 0), new(2000, 2000), new(0, 2000), new(0, 0)
        };
        var options = new ParserOptions { RasterWallDilateMm = 450, RasterMmPerPx = 50 };
        var walls = Enumerable.Range(0, 4)
            .Select(i => new List<Coordinate> { corners[i], corners[i + 1] }).ToList();
        var rebuilt = RasterRoomReconstructor.Rebuild(
            walls, new List<List<Coordinate>>(),
            new Envelope(-200, 2200, -200, 2200), options);

        Assert.True(rebuilt.Spaces.Count == 1,
            $"components={rebuilt.ComponentCount} droppedArea={rebuilt.DroppedByArea} droppedEdge={rebuilt.DroppedByEdge}");
        var space = rebuilt.Spaces[0];
        Assert.True(space.Polygon.Area / 1e6 >= options.MinRoomAreaM2);
        Assert.InRange(space.Polygon.Area / 1e6, 3.5, 4.5);
    }

    [Fact]
    public void MultipleVisibleLabelsSplitOnlyMergedRasterRegion()
    {
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        var polygon = factory.CreatePolygon(new[]
        {
            new Coordinate(0, 0), new(10000, 0), new(10000, 4000),
            new(0, 4000), new(0, 0)
        });
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "左室", TextPos = new Coordinate(2500, 2000) },
            new() { Kind = FlatKind.Text, Text = "右室", TextPos = new Coordinate(7500, 2000) }
        };

        var split = RasterLabelPartitioner.Split(new[] { new AlignedSpace { Polygon = polygon } },
            texts, new Regex("左室|右室"), 1.5, out int splitCount);

        Assert.Equal(1, splitCount);
        Assert.Equal(2, split.Count);
        Assert.Equal(new[] { "右室", "左室" }, split.Select(s => s.Label).OrderBy(s => s).ToArray());
        Assert.True(Math.Abs(split.Sum(s => s.Polygon.Area) - polygon.Area) < 1);
        Assert.True(split[0].Polygon.Intersection(split[1].Polygon).Area < 1);
        Assert.All(split.SelectMany(s => s.Polygon.ExteriorRing.Coordinates.Zip(
                s.Polygon.ExteriorRing.Coordinates.Skip(1))), pair =>
            Assert.True(Math.Abs(pair.First.X - pair.Second.X) < 1
                || Math.Abs(pair.First.Y - pair.Second.Y) < 1));
    }

    [Fact]
    public void DiagonalLabelsUseAxisAlignedEstimatedBoundary()
    {
        var factory = NtsGeometryServices.Instance.CreateGeometryFactory();
        var polygon = factory.CreatePolygon(new[]
        {
            new Coordinate(0, 0), new(8000, 0), new(8000, 6000),
            new(0, 6000), new(0, 0)
        });
        var texts = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "左上室", TextPos = new Coordinate(2000, 4500) },
            new() { Kind = FlatKind.Text, Text = "右下室", TextPos = new Coordinate(6500, 1500) }
        };
        var split = RasterLabelPartitioner.Split(new[] { new AlignedSpace { Polygon = polygon } },
            texts, new Regex("左上室|右下室"), 1.5, out int splitCount);
        Assert.Equal(1, splitCount);
        Assert.Equal(2, split.Count);
        Assert.All(split.SelectMany(s => s.Polygon.ExteriorRing.Coordinates.Zip(
                s.Polygon.ExteriorRing.Coordinates.Skip(1))), pair =>
            Assert.True(Math.Abs(pair.First.X - pair.Second.X) < 1
                || Math.Abs(pair.First.Y - pair.Second.Y) < 1));
    }

    [Fact]
    public void VisibleLabelCanRecoverRoomWhoseDilatedCoreDisappears()
    {
        var points = new[]
        {
            new Coordinate(0, 0), new Coordinate(5000, 0),
            new Coordinate(5000, 4000), new Coordinate(0, 4000), new Coordinate(0, 0)
        };
        var walls = Enumerable.Range(0, 4)
            .Select(i => new List<Coordinate> { points[i], points[i + 1] }).ToList();
        walls.Add(new List<Coordinate> { new(1100, 0), new(1100, 4000) });
        var options = new ParserOptions { RasterWallDilateMm = 600, RasterMmPerPx = 50 };
        var roomLabels = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Text = "窄室", TextPos = new Coordinate(550, 2000) }
        };
        var rebuilt = RasterRoomReconstructor.Rebuild(walls, new List<List<Coordinate>>(),
            new Envelope(-200, 5200, -200, 4200), options, roomLabels: roomLabels);

        Assert.Equal(1, rebuilt.SeededByLabel);
        Assert.Contains(rebuilt.Spaces, s => s.Label == "窄室" && s.Polygon.Area / 1e6 >= 1.5);
    }
}
