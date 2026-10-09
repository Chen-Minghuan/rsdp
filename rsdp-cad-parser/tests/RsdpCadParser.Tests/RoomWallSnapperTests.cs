using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

/// <summary>
/// RoomWallSnapper 契约测试（P2-S 房间轮廓贴合墙体内侧面）：
/// 共线无间隙 / 顶点=内侧面交点 / 不压墙 / 共线碎段合并 / 门洞折线拉直 /
/// 无 Inner Face 保持原样+质量提示 / 面积超阈值回退。
/// 几何均为毫米坐标合成场景。
/// </summary>
public class RoomWallSnapperTests
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    private static Polygon Rect(double x0, double y0, double x1, double y1)
        => Factory.CreatePolygon(new[]
        {
            new Coordinate(x0, y0), new(x1, y0), new(x1, y1), new(x0, y1), new(x0, y0)
        });

    /// <summary>四面墙实心（墙厚 200）围出 4000×3000 净空间的 WallUnion。</summary>
    private static Geometry FourWalls(double x0, double y0, double x1, double y1, double t = 200)
        => NetTopologySuite.Operation.Union.UnaryUnionOp.Union(new Geometry[]
        {
            Rect(x0 - t, y0 - t, x1 + t, y0), // 下墙
            Rect(x0 - t, y1, x1 + t, y1 + t), // 上墙
            Rect(x0 - t, y0, x0, y1),         // 左墙
            Rect(x1, y0, x1 + t, y1),         // 右墙
        });

    private static WallInnerFaceSet FacesFrom(Geometry wallUnion, List<FlatEntity>? finishLines = null)
    {
        var walls = new WallExtractor.WallExtractionResult { WallUnion = wallUnion };
        return WallInnerFaceExtractor.Build(finishLines ?? new List<FlatEntity>(), walls, new ParserOptions());
    }

    private static FlatEntity FinishLine(string layer, params Coordinate[] pts)
        => new() { Kind = FlatKind.Polyline, Layer = layer, Points = pts.ToList() };

    [Fact]
    public void Snap_EdgesBecomeCollinearWithInnerFace_NoGapNoOffset()
    {
        // 房间轮廓比内侧面整体内缩 80mm（模拟栅格/简化偏差）→ 吸附后每边与内侧面共线同坐标
        var union = FourWalls(0, 0, 4000, 3000);
        var room = Rect(80, 80, 3920, 2920);
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        Assert.True(r.Changed);
        Assert.Equal(r.EdgesTotal, r.EdgesSnapped);
        // 顶点 = 内侧面（墙实心边界）坐标：净空间四角
        var pts = r.Polygon.ExteriorRing.Coordinates;
        Assert.Contains(pts, p => p.X == 0 && p.Y == 0);
        Assert.Contains(pts, p => p.X == 4000 && p.Y == 3000);
        // 每条边与墙实心边界的距离为 0（共线无间隙），且不进入墙体
        foreach (var (a, b) in pts.Zip(pts.Skip(1)))
        {
            var edge = Factory.CreateLineString(new[] { a, b });
            Assert.Equal(0, edge.Distance(union.Boundary), 6);
        }
        Assert.Equal(0, r.Polygon.Intersection(union).Area, 3);
        Assert.Equal(12.0, r.Polygon.Area / 1e6, 1);
    }

    [Fact]
    public void Snap_CornerVertexIsIntersectionOfInnerFaceLines()
    {
        // 房间轮廓四角外凸 50mm → 吸附后角点 = 两条内侧面直线交点（不是各算各的投影）
        var union = FourWalls(0, 0, 4000, 3000);
        var room = Factory.CreatePolygon(new[]
        {
            new Coordinate(-50, 100), new(3950, 50), new(4000, 2950), new(50, 3000), new(-50, 100)
        });
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        var pts = r.Polygon.ExteriorRing.Coordinates.Take(4).ToList();
        Assert.Equal(4, pts.Count);
        // 每个顶点必须是 x∈{0,4000} 且 y∈{0,3000} 的精确交点
        Assert.All(pts, p =>
        {
            Assert.True(p.X == 0 || p.X == 4000, $"顶点 X={p.X} 不在内侧面直线上");
            Assert.True(p.Y == 0 || p.Y == 3000, $"顶点 Y={p.Y} 不在内侧面直线上");
        });
    }

    [Fact]
    public void Snap_NeverIntrudesIntoWallSolid()
    {
        // 房间轮廓部分边已经越过内侧面伸进墙实心 120mm → 吸附把边拉回内侧面，不压墙
        var union = FourWalls(0, 0, 4000, 3000);
        var room = Rect(-120, 60, 4000, 3000); // 左边压进左墙
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        Assert.Equal(0, r.Polygon.Intersection(union).Area, 3);
        // 左边应被拉到 x=0（左墙内侧面）
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p.X == 0);
    }

    [Fact]
    public void Snap_CollinearFragmentsMergedIntoSingleEdge()
    {
        // 同一段直墙被生成多段轻微偏移（±15mm）小线段 → 吸附+共线合并后为一条直边
        var union = FourWalls(0, 0, 6000, 3000);
        var room = Factory.CreatePolygon(new[]
        {
            new Coordinate(15, 10), new(1500, -12), new(3200, 8), new(4700, -10), new(6000, 12),
            new(5990, 2990), new(3000, 3010), new(10, 2988), new(15, 10)
        });
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        Assert.Equal(5, r.Polygon.ExteriorRing.NumPoints); // 矩形：4 顶点 + 闭合点
        Assert.All(r.Polygon.ExteriorRing.Coordinates.Take(4), p =>
            Assert.True((p.X == 0 || p.X == 6000) && (p.Y == 0 || p.Y == 3000),
                $"合并后顶点 ({p.X},{p.Y}) 应为净空间角点"));
    }

    [Fact]
    public void Snap_DoorChainStraightened_PerpendicularToFlankingFaces()
    {
        // 下墙开 900mm 门洞：房间下边在门洞处呈不规则折线（虚拟封口）→ 拉直为一条边
        var union = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(new Geometry[]
        {
            Rect(-200, -200, 1550, 0), Rect(2450, -200, 4200, 0), // 下墙两段（门洞 1550~2450）
            Rect(-200, 3000, 4200, 3200),                          // 上墙
            Rect(-200, 0, 0, 3000), Rect(4000, 0, 4200, 3000),     // 左右墙
        });
        var room = Factory.CreatePolygon(new[]
        {
            new Coordinate(30, 40), new(1500, 20), new(1700, -60), new(2100, -80), new(2400, -30), // 门洞折线
            new(3980, 25), new(4010, 2980), new(2000, 3020), new(20, 2990), new(30, 40)
        });
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        Assert.True(r.DoorChainsStraightened >= 1, "门洞折线链应被拉直");
        // 拉直后下边（含门洞段）在 y=0 直线上：底边两顶点 y 均为 0
        var pts = r.Polygon.ExteriorRing.Coordinates;
        Assert.Equal(5, pts.Length); // 矩形
        Assert.Contains(pts, p => p is { X: 1550, Y: 0 } or { X: 2450, Y: 0 }
                                 || p.Y == 0);
        Assert.Equal(0, r.Polygon.Intersection(union).Area, 3);
    }

    [Fact]
    public void Snap_NoInnerFace_KeepsOriginalAndFlags()
    {
        // 无任何内侧面来源 → 原样返回并记 WALL_FACE_NOT_FOUND
        var room = Rect(0, 0, 3000, 2500);
        var emptyFaces = new WallInnerFaceSet();

        var r = RoomWallSnapper.SnapRoom(room, emptyFaces, null, new ParserOptions());

        Assert.False(r.Changed);
        Assert.False(r.Reverted);
        Assert.Same(room, r.Polygon);
        // 空集合时 SnapAll 也不应产生任何质量门（无内侧面 ≠ 部分边未命中）
        var issues = new List<QualityIssue>();
        RoomWallSnapper.SnapAll(new List<AlignedSpace> { new() { Polygon = room } },
            emptyFaces, null, new ParserOptions(), issues, null, "");
        Assert.Empty(issues);
    }

    [Fact]
    public void Snap_EdgeWithoutFace_KeepsOriginalEdgeAndFlags()
    {
        // 房间三边有内侧面、一侧开放（无墙）→ 开放边保持原样并记 WALL_FACE_NOT_FOUND
        var union = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(new Geometry[]
        {
            Rect(-200, -200, 4200, 0),       // 下墙
            Rect(-200, 0, 0, 3000),          // 左墙
            Rect(4000, 0, 4200, 3000),       // 右墙
        });
        var room = Rect(50, 60, 3950, 2900); // 上边（y≈2900）无对应内侧面
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        Assert.True(r.Changed);
        Assert.Contains("WALL_FACE_NOT_FOUND", r.Codes);
        // 开放侧边保持原坐标（y=2900），不虚构边界
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => Math.Abs(p.Y - 2900) < 1);
        // 有墙三边贴合内侧面
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p.X == 0);
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p.X == 4000);
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p.Y == 0);
    }

    [Fact]
    public void RefreshFaceNotFoundIssue_UsesFinalRoomWithoutChangingItsGeometry()
    {
        var union = NetTopologySuite.Operation.Union.UnaryUnionOp.Union(new Geometry[]
        {
            Rect(-200, -200, 4200, 0),
            Rect(-200, 0, 0, 3000),
            Rect(4000, 0, 4200, 3000)
        });
        var finalPolygon = Rect(0, 0, 4000, 2900);
        var spaces = new List<AlignedSpace> { new() { Label = "客厅", Polygon = finalPolygon } };
        var issues = new List<QualityIssue>
        {
            new() { Level = "warn", Code = "WALL_FACE_NOT_FOUND", Message = "客厅(9/15边吸附)" }
        };

        RoomWallSnapper.RefreshFaceNotFoundIssue(spaces, FacesFrom(union), union,
            new ParserOptions(), issues);

        Assert.Same(finalPolygon, spaces[0].Polygon);
        Assert.Single(issues);
        Assert.Contains("客厅(3/4边匹配)", issues[0].Message);
        Assert.DoesNotContain("9/15", issues[0].Message);
    }

    [Fact]
    public void Snap_AreaChangeBeyondThreshold_Reverts()
    {
        // 小房间 1000×1000，内侧面在轮廓内 150mm（吸附后 700×700，面积 −51%）→ 超 15% 阈值回退
        var union = FourWalls(150, 150, 850, 850);
        var room = Rect(0, 0, 1000, 1000);
        var faces = FacesFrom(union);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.True(r.Reverted);
        Assert.False(r.Changed);
        Assert.Contains("WALL_SNAP_REVERTED", r.Codes);
        Assert.Same(room, r.Polygon);
        Assert.Equal(1.0, r.Polygon.Area / 1e6, 3);
    }

    [Fact]
    public void Snap_FinishLayerHasPriorityOverWallBoundary()
    {
        // 完成面线（优先级 0）与实心墙边界（优先级 1）平行且都在容差内时，吸附到完成面线
        var union = FourWalls(0, 0, 4000, 3000); // 内侧面 y=0 / y=3000
        var finish = new List<FlatEntity>
        {
            FinishLine("W装饰完成面", new Coordinate(-200, 30), new Coordinate(4200, 30)),
            FinishLine("W装饰完成面", new Coordinate(-200, 2970), new Coordinate(4200, 2970)),
            FinishLine("W装饰完成面", new Coordinate(30, -200), new Coordinate(30, 3200)),
            FinishLine("W装饰完成面", new Coordinate(3970, -200), new Coordinate(3970, 3200)),
        };
        var room = Rect(60, 55, 3940, 2940);
        var walls = new WallExtractor.WallExtractionResult { WallUnion = union };
        var faces = WallInnerFaceExtractor.Build(finish, walls, new ParserOptions());
        Assert.Contains(faces.Segments, s => s.Source == InnerFaceSource.FinishLayer);

        var r = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.False(r.Reverted);
        // 顶点应落在完成面线上（y=30 / y=2970 / x=30 / x=3970），而非墙实心边界
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p is { X: 30, Y: 30 });
        Assert.Contains(r.Polygon.ExteriorRing.Coordinates, p => p is { X: 3970, Y: 2970 });
    }

    [Fact]
    public void Snap_Deterministic_SameInputSameOutput()
    {
        var union = FourWalls(0, 0, 4000, 3000);
        var room = Rect(73, 81, 3917, 2929);
        var faces = FacesFrom(union);

        var r1 = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());
        var r2 = RoomWallSnapper.SnapRoom(room, faces, union, new ParserOptions());

        Assert.Equal(
            r1.Polygon.ExteriorRing.Coordinates.Select(c => (c.X, c.Y)).ToArray(),
            r2.Polygon.ExteriorRing.Coordinates.Select(c => (c.X, c.Y)).ToArray());
    }
}
