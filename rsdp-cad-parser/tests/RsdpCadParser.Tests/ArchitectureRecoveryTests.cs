using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

public class ArchitectureRecoveryTests
{
    [Fact]
    public void NestedZeroLayer_InheritsFurnitureParentThroughWholePath()
    {
        var path=Path.Combine(AppContext.BaseDirectory,"Fixtures","nested_insert.dxf");
        var doc=CadDocumentLoader.Load(File.ReadAllBytes(path),Path.GetFileName(path));
        var resolved=BlockResolver.Resolve(doc,1,new ParserOptions(),CancellationToken.None);
        var root=Assert.Single(resolved.Roots);
        Assert.Equal("家具",root.Layer);
        Assert.All(root.Children,child=>Assert.Equal("家具",child.Layer));
        Assert.All(resolved.ExpandedEntities,e=>Assert.Equal("家具",e.EffectiveLayer));
        var chairEntities = resolved.ExpandedEntities
            .Where(e => e.BlockDefinitionName == "CHAIR").ToList();
        Assert.Equal(2, chairEntities.Count);
        Assert.All(chairEntities,e=>Assert.Equal(2,e.InsertPath!.Count));
    }

    [Fact]
    public void PairingAboveThreeThousandSegments_PreservesLocalWallPairs()
    {
        var walls=new WallExtractor.WallExtractionResult();
        walls.WallLines.Add(new(){new(0,0),new(2000,0)});
        walls.WallLines.Add(new(){new(0,200),new(2000,200)});
        for(int i=0;i<3100;i++)walls.WallLines.Add(new(){new(10000+i*1000,0),new(10500+i*1000,0)});
        var faces=WallInnerFaceExtractor.Build(Array.Empty<FlatEntity>(),walls,new ParserOptions());
        Assert.Equal(InnerFaceSource.WallLinePair,faces.Segments[0].Source);
        Assert.Equal(InnerFaceSource.WallLinePair,faces.Segments[1].Source);
    }

    [Theory]
    [InlineData(true,1)]
    [InlineData(false,0)]
    public void DoorThreshold_RequiresSupportedDoorwayEnds(bool supported,int expected)
    {
        var walls=new WallExtractor.WallExtractionResult();
        var arc=Enumerable.Range(0,19).Select(i=>new Coordinate(900*Math.Cos(i*Math.PI/36),900*Math.Sin(i*Math.PI/36))).ToList();
        var leaf=new List<Coordinate>{new(0,0),new(0,900)};
        walls.DoorLines.Add(arc);walls.DoorLines.Add(leaf);
        walls.WallLines.Add(new(){new(0,-500),new(0,0)});
        if(supported)walls.WallLines.Add(new(){new(900,0),new(900,-500)});
        Assert.Equal(expected,DoorBoundaryReconstructor.Apply(walls));
        if(supported)
        {
            var threshold=Assert.Single(walls.DoorLines);
            Assert.True(threshold[0].Distance(new Coordinate(0,0))<0.01);
            Assert.True(threshold[1].Distance(new Coordinate(900,0))<0.01);
        }
        else Assert.Empty(walls.DoorLines);
    }

    [Fact]
    public void DoorThreshold_UsesSupportedLeafTipWhenDoorIsClosed()
    {
        var walls = new WallExtractor.WallExtractionResult();
        var arc = Enumerable.Range(0, 19)
            .Select(index => new Coordinate(900 * Math.Cos(index * Math.PI / 36),
                900 * Math.Sin(index * Math.PI / 36))).ToList();
        walls.DoorLines.Add(arc);
        walls.DoorLines.Add(new List<Coordinate> { new(0, 0), new(0, 900) });
        walls.WallLines.Add(new List<Coordinate> { new(0, -500), new(0, 0) });
        walls.WallLines.Add(new List<Coordinate> { new(0, 900), new(0, 1400) });

        Assert.Equal(1, DoorBoundaryReconstructor.Apply(walls));
        var threshold = Assert.Single(walls.DoorLines);
        Assert.True(threshold[0].Distance(new Coordinate(0, 0)) < 0.01);
        Assert.True(threshold[1].Distance(new Coordinate(0, 900)) < 0.01);
    }

    [Fact]
    public void DoorSwingArcWithoutLeaf_IsNotASeparatingWall()
    {
        var walls = new WallExtractor.WallExtractionResult();
        walls.DoorLines.Add(Enumerable.Range(0, 19)
            .Select(index => new Coordinate(900 * Math.Cos(index * Math.PI / 36),
                900 * Math.Sin(index * Math.PI / 36))).ToList());
        var straightDoorLine = new List<Coordinate> { new(2000, 0), new(2000, 900) };
        walls.DoorLines.Add(straightDoorLine);

        Assert.Equal(0, DoorBoundaryReconstructor.Apply(walls));
        Assert.Same(straightDoorLine, Assert.Single(walls.DoorLines));
    }

    [Fact]
    public void FixedCabinet_CombinesAdjacentCellsWithoutInternalLinesOrActivityFurniture()
    {
        FlatEntity Rect(double x,string layer)=>new(){Kind=FlatKind.Polyline,Layer=layer,Closed=true,
            Points=new(){new(x,0),new(x+500,0),new(x+500,500),new(x,500)},BBox=new(x,x+500,0,500)};
        var boundaries=FunctionalBoundarySelector.Select(new[]{Rect(0,"J固定家具"),Rect(500,"J固定家具"),Rect(2000,"J活动家具")},new Envelope(-100,4000,-100,1000));
        var boundary=Assert.Single(boundaries);
        Assert.Equal(1000,boundary.BBox.Width);
        Assert.Equal("功能分隔（固定柜体）",boundary.Layer);
        Assert.DoesNotContain(boundary.Points!,p=>p.X==500 && p.Y>0 && p.Y<500);
    }
}
