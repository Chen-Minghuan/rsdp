using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class CanonicalPreviewSelectorTests
{
    [Fact]
    public void Select_KeepsBuildingFurnitureAndApplianceDetails()
    {
        var directWall = Line("0");
        var door = Line("0");
        var sofaDetail = Line("0");
        var applianceDetail = Line("0");
        var arch = Node("WALL", "W新建墙体", door);
        var furniture = Node("SOFA", "J活动家具", sofaDetail);
        var appliance = Node("UNKNOWN", "J家电设备", applianceDetail);

        var selected = CanonicalPreviewSelector.Select(
            new[] { directWall }, new[] { arch, furniture, appliance }, new ParserOptions());

        Assert.Contains(directWall, selected);
        Assert.Contains(door, selected);
        Assert.Contains(sofaDetail, selected);
        Assert.Contains(applianceDetail, selected);
    }

    [Fact]
    public void Select_UnknownOnlyBlockFallsBackToOriginalInsteadOfBlankPreview()
    {
        var line = Line("0");
        var selected = CanonicalPreviewSelector.Select(
            Array.Empty<FlatEntity>(), new[] { Node("UNKNOWN", "0", line) }, new ParserOptions());

        Assert.Single(selected);
        Assert.Same(line, selected[0]);
    }

    [Fact]
    public void Select_RemovesDimensionAndMechanicalNoise()
    {
        var wall = Line("W新建墙体");
        var noise = Line("机电点位");
        var dimension = Line("尺寸标注");
        var result = CanonicalPreviewSelector.Select(new[] { wall, noise, dimension },
            Array.Empty<BlockResolver.InsertNode>(), new ParserOptions());
        Assert.Single(result);
        Assert.Same(wall, result[0]);
    }

    private static FlatEntity Line(string layer) => new()
    {
        Kind = FlatKind.Line,
        Layer = layer,
        Points = new List<Coordinate> { new(0, 0), new(1000, 0) },
        BBox = new Envelope(0, 1000, 0, 0)
    };

    private static BlockResolver.InsertNode Node(string name, string layer, FlatEntity line)
    {
        var node = new BlockResolver.InsertNode { BlockName = name, Layer = layer };
        node.Primitives.Add(line);
        return node;
    }
}
