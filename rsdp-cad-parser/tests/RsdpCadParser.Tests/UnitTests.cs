using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class UnitTests
{
    [Fact]
    public void MTextCleaner_StripsFormatCodes()
    {
        Assert.Equal("客厅", MTextCleaner.Clean(@"{\fFangSong|b0|i0|c134|p49;\C3;客厅}"));
        Assert.Equal("主卧 卫生间", MTextCleaner.Clean(@"主卧\P卫生间"));
        Assert.Equal("", MTextCleaner.Clean(null));
        Assert.Equal("φ800", MTextCleaner.Clean("%%c800"));
    }

    [Fact]
    public void AnnotationExtractor_FindsAreaTexts()
    {
        var entities = new List<FlatEntity>
        {
            new() { Kind = FlatKind.Text, Layer = "地面铺贴图", Text = "面积: 22.75㎡", TextPos = new Coordinate(100, 100) },
            new() { Kind = FlatKind.Text, Layer = "地面铺贴图", Text = "周长: 26.83m", TextPos = new Coordinate(100, 90) },
            new() { Kind = FlatKind.Text, Layer = "0", Text = "客厅", TextPos = new Coordinate(50, 50) },
        };
        var ann = AnnotationExtractor.ExtractAreaAnnotations(entities);
        Assert.Single(ann);
        Assert.Equal(22.75, ann[0].AreaM2);
    }

    [Fact]
    public void SubDrawingClusterer_SplitsByGap()
    {
        // 两簇：x≈0 与 x≈20000（20m 间距 > 10m 阈值）
        var entities = new List<FlatEntity>();
        for (int i = 0; i < 3; i++)
            entities.Add(MkText($"客厅{i}", new Coordinate(i * 100, 0)));
        for (int i = 0; i < 2; i++)
            entities.Add(MkText($"卧室{i}", new Coordinate(20_000 + i * 100, 0)));

        var clusters = SubDrawingClusterer.Split(entities, new ParserOptions());
        Assert.Equal(2, clusters.Count);
        Assert.Equal(3, clusters[0].LabelHits);
        Assert.Equal(2, clusters[1].LabelHits);
    }

    [Fact]
    public void TargetSelector_PicksRegionWithMostAnnotations()
    {
        var opt = new ParserOptions();
        var entities = new List<FlatEntity>
        {
            MkText("客厅", new Coordinate(0, 0)),
            MkText("主卧", new Coordinate(3000, 0)),
            MkText("客厅", new Coordinate(100_000, 0)),
            MkText("主卧", new Coordinate(103_000, 0)),
        };
        var annotations = new List<AreaAnnotation>
        {
            new(22.75, new Coordinate(100_000, 0), "地面铺贴图", "面积: 22.75㎡"),
            new(10.30, new Coordinate(103_000, 0), "地面铺贴图", "面积: 10.30㎡"),
        };
        var target = TargetSelector.Select(entities, annotations, opt);
        Assert.True(target.FromLabels);
        Assert.Equal(2, target.AnnotationCount);
        Assert.True(target.Region.MinX > 50_000, "目标区域应在第二副本（有标注的一侧）");
    }

    private static FlatEntity MkText(string text, Coordinate pos) => new()
    {
        Kind = FlatKind.Text,
        Layer = "0",
        Text = text,
        TextPos = pos,
        BBox = new Envelope(pos)
    };
}
