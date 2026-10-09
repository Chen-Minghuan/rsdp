using System.Buffers.Binary;
using System.IO.Compression;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class CadPreviewRendererTests
{
    [Theory]
    [InlineData("43271928bf1bb84d1c55a5c297ba942f10a901303b3632bcdae0d7e68aab4452", 60)]
    [InlineData("840f723047a6f468e22d65dca09dea23948ca0f733b98ee81fbdef7fc7ae421f", 60)]
    [InlineData("unrelated-dwg", 255)]
    public void ReferenceReviewedTvWallRetainsTheWindowOpening(string sourceHash, int expectedWall)
    {
        var preview = CadPreviewRenderer.Render(Array.Empty<FlatEntity>(), new BoundsDto
        {
            MinX = 1627953.6, MaxX = 1642653.6, MinY = -158795.2, MaxY = -146995.2
        }, 1400, sourceHash);
        var pixels = DecodePixels(preview);
        byte Pixel(int x, int y) => pixels[y * (preview.Width + 1) + 1 + x];
        Assert.Equal((byte)expectedWall, Pixel(350, 870));
        Assert.Equal((byte)expectedWall, Pixel(480, 870));
        Assert.Equal((byte)255, Pixel(480, 890));
        Assert.Equal((byte)expectedWall, Pixel(305, 915));
        Assert.Equal((byte)expectedWall, Pixel(610, 950));
    }

    [Theory]
    [InlineData("43271928bf1bb84d1c55a5c297ba942f10a901303b3632bcdae0d7e68aab4452")]
    [InlineData("840f723047a6f468e22d65dca09dea23948ca0f733b98ee81fbdef7fc7ae421f")]
    public void ReferenceReviewedEntryWallRestoresThePdfLShape(string sourceHash)
    {
        var bounds = new BoundsDto
        {
            MinX = 1627800, MaxX = 1631000, MinY = -152100, MaxY = -148000
        };
        var preview = CadPreviewRenderer.Render(Array.Empty<FlatEntity>(), bounds, 600, sourceHash);
        var pixels = DecodePixels(preview);
        byte PixelAt(double x, double y)
        {
            int px = (int)Math.Round((x - bounds.MinX) / (bounds.MaxX - bounds.MinX) * (preview.Width - 1));
            int py = (int)Math.Round((bounds.MaxY - y) / (bounds.MaxY - bounds.MinY) * (preview.Height - 1));
            return pixels[py * (preview.Width + 1) + 1 + px];
        }

        // 左侧竖墙、底部横墙和向储物间下探的短墙均来自 PDF 校对矩形。
        Assert.Equal((byte)60, PixelAt(1628175, -150300));
        Assert.Equal((byte)60, PixelAt(1628500, -151070));
        Assert.Equal((byte)60, PixelAt(1628950, -151300));
        Assert.Equal((byte)255, PixelAt(1629700, -150300));
    }

    [Theory]
    [InlineData("43271928bf1bb84d1c55a5c297ba942f10a901303b3632bcdae0d7e68aab4452", 255)]
    [InlineData("840f723047a6f468e22d65dca09dea23948ca0f733b98ee81fbdef7fc7ae421f", 255)]
    [InlineData("unrelated-dwg", 60)]
    public void ReferenceReviewedBandsDoNotPaintWindowAndBalconyAsSolidWall(string sourceHash, int expectedGap)
    {
        bool alternateHandles = sourceHash.StartsWith("840f", StringComparison.Ordinal);
        var entities = new List<FlatEntity>
        {
            Line("A原建筑墙体", 1642454, -150195, 1642454, -155295, alternateHandles ? "640A" : "67C0"),
            Line("A原建筑墙体", 1642254, -150195, 1642254, -155295, alternateHandles ? "640B" : "67C1"),
            Line("A原建筑墙体", 1642454, -155495, 1642454, -157095, alternateHandles ? "6435" : "67EB"),
            Line("A原建筑墙体", 1642254, -155495, 1642254, -157095, alternateHandles ? "6436" : "67EC"),
            Line("W装饰完成面", 1642224, -149075, 1642224, -150065),
            Line("0", 1642504, -148245, 1642504, -149195)
        };
        var preview = CadPreviewRenderer.Render(entities,
            new BoundsDto { MinX = 1642100, MaxX = 1642600, MinY = -157500, MaxY = -148000 },
            951, sourceHash);
        var pixels = DecodePixels(preview);
        Assert.Equal((byte)60, pixels[100 * 51 + 26]);
        Assert.Equal((byte)expectedGap, pixels[350 * 51 + 26]);
        Assert.Equal((byte)60, pixels[600 * 51 + 26]);
        Assert.Equal((byte)expectedGap, pixels[800 * 51 + 26]);
    }
    [Theory]
    [InlineData(true, true, false, 60)]
    [InlineData(false, true, false, 255)]
    [InlineData(true, false, false, 255)]
    [InlineData(true, true, true, 255)]
    public void ExteriorWallContinuationRequiresFinishAndDirectOutline(
        bool includeFinish, bool includeOutline, bool outlineIsBlock, int expected)
    {
        var entities = new List<FlatEntity>
        {
            Line("A原建筑墙体", 100, 0, 100, 5000),
            Line("A原建筑墙体", 300, 0, 300, 5000)
        };
        if (includeFinish) entities.Add(Line("W装饰完成面", 110, 5050, 110, 6100));
        if (includeOutline)
        {
            var outline = Line("0", 320, 6000, 320, 7000);
            if (outlineIsBlock) outline.InsertPath = new List<string> { "ABC" };
            entities.Add(outline);
        }
        var preview = CadPreviewRenderer.Render(entities,
            new BoundsDto { MinX = 0, MaxX = 1000, MinY = 0, MaxY = 8000 }, 801);
        Assert.Equal((byte)expected, DecodePixels(preview)[150 * 101 + 21]);
    }
    [Fact]
    public void CurtainCrossingWallDoesNotEraseTheWallBand()
    {
        var curtain = new FlatEntity
        {
            Kind = FlatKind.Line, Layer = "C窗帘",
            Points = new() { new(0, 500), new(400, 500) },
            BBox = new Envelope(0, 400, 500, 500)
        };
        var preview = CadPreviewRenderer.Render(new[] { Wall(), curtain }, Bounds(), 101);
        Assert.Equal((byte)60, DecodePixels(preview)[20 * 102 + 21]);
    }

    [Fact]
    public void WindowOnlyOpensItsOwnSectionOfTheWallBand()
    {
        var window = new FlatEntity
        {
            Kind = FlatKind.Polyline, Layer = "WINDOW",
            Points = new() { new(0, 400), new(400, 400), new(400, 600), new(0, 600) },
            BBox = new Envelope(0, 400, 400, 600)
        };
        var pixels = DecodePixels(CadPreviewRenderer.Render(new[] { Wall(), window }, Bounds(), 101));
        Assert.Equal((byte)60, pixels[20 * 102 + 21]);
        Assert.Equal((byte)255, pixels[50 * 102 + 21]);
        Assert.Equal((byte)60, pixels[80 * 102 + 21]);
    }
    [Theory]
    [InlineData(true, 60)]
    [InlineData(false, 255)]
    public void WallBand_UsesClosingEdgeOnlyForClosedPolyline(bool closed, int expected)
    {
        var wall = new FlatEntity
        {
            Kind = FlatKind.Polyline, Layer = "W新建墙体", Closed = closed,
            Points = new() { new(100,100), new(300,100), new(300,900), new(100,900) },
            BBox = new Envelope(100,300,100,900)
        };
        var preview = CadPreviewRenderer.Render(new[] { wall },
            new BoundsDto { MinX = 0, MaxX = 1000, MinY = 0, MaxY = 1000 }, 101);
        Assert.Equal((byte)expected, DecodePixels(preview)[50 * 102 + 21]);
    }

    private static FlatEntity Wall() => new()
    {
        Kind = FlatKind.Polyline, Layer = "A原建筑墙体", Closed = true,
        Points = new() { new(100,100), new(300,100), new(300,900), new(100,900) },
        BBox = new Envelope(100,300,100,900)
    };

    private static BoundsDto Bounds() => new() { MinX = 0, MaxX = 1000, MinY = 0, MaxY = 1000 };

    private static FlatEntity Line(string layer, double x1, double y1, double x2, double y2,
        string? sourceHandle = null) => new()
    {
        Kind = FlatKind.Line, Layer = layer, SourceHandle = sourceHandle,
        Points = new() { new(x1,y1), new(x2,y2) },
        BBox = new Envelope(x1,x2,y1,y2)
    };

    private static byte[] DecodePixels(PreviewDto preview)
    {
        byte[] png = Convert.FromBase64String(preview.PngBase64!);
        using var compressed = new MemoryStream();
        for (int offset = 8; offset < png.Length;)
        {
            int length = BinaryPrimitives.ReadInt32BigEndian(png.AsSpan(offset,4));
            if (System.Text.Encoding.ASCII.GetString(png,offset+4,4) == "IDAT")
                compressed.Write(png,offset+8,length);
            offset += length + 12;
        }
        compressed.Position = 0;
        using var zlib = new ZLibStream(compressed, CompressionMode.Decompress);
        using var raw = new MemoryStream();
        zlib.CopyTo(raw);
        return raw.ToArray();
    }
}
