using NetTopologySuite;
using NetTopologySuite.Geometries;
using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

/// <summary>
/// P0 契约测试（设计文档 §18/§42.1）：INSERT 家具识别、稳定 ID、单位判定、
/// parseStatus 映射、非家具排除、目标场景候选与手动切换、资源守卫。
/// fixture 为手写最小 ASCII DXF（tests/RsdpCadParser.Tests/Fixtures/）。
/// </summary>
public class P0ContractTests
{
    private static string FixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", name);

    private static CadParseResult ParseFixture(string name, ParserOptions? opt = null, string? targetId = null)
        => CadParsePipeline.Parse(File.ReadAllBytes(FixturePath(name)), name, opt, targetId);

    // ① 同一 BLOCK 两次 INSERT → 两个家具实例、ID 不同；重复解析 ID 稳定
    [Fact]
    public void TwoInserts_TwoDistinctStableInstances()
    {
        var r1 = ParseFixture("two_inserts.dxf");
        Assert.True(r1.Success);
        var f1 = Assert.IsType<List<FurnitureDto>>(r1.Furniture);
        Assert.Equal(2, f1.Count);
        Assert.All(f1, f => Assert.Equal("SOFA", f.Category));
        Assert.All(f1, f => Assert.Equal("INSERT", f.SourceKind));
        Assert.Equal(2, f1.Select(f => f.FurnitureId).Distinct().Count());
        Assert.All(f1, f => Assert.StartsWith("F-", f.FurnitureId));

        // 同块定义展开：块局部尺寸一致（2000×900）
        Assert.All(f1, f => Assert.Equal(2000, f.GeometryWidthMm));
        Assert.All(f1, f => Assert.Equal(900, f.GeometryDepthMm));
        // 顶层 INSERT 旋转角
        Assert.Contains(f1, f => f.RotationDeg == 0);
        Assert.Contains(f1, f => f.RotationDeg == 90);
        // footprint 为闭合矩形
        Assert.All(f1, f =>
        {
            var p = Assert.IsType<double[][]>(f.FootprintPolygon);
            Assert.Equal(5, p.Length);
            Assert.Equal(p[0], p[^1]);
        });

        // 重复解析：ID 稳定（纯函数、可重现）
        var r2 = ParseFixture("two_inserts.dxf");
        Assert.Equal(
            f1.Select(f => f.FurnitureId).OrderBy(x => x).ToArray(),
            r2.Furniture!.Select(f => f.FurnitureId).OrderBy(x => x).ToArray());
    }

    // ② 嵌套 INSERT 展开：通用容器下钻，子块带完整 insertPath
    [Fact]
    public void NestedInsert_ExpandsChildrenWithInsertPath()
    {
        var r = ParseFixture("nested_insert.dxf");
        Assert.True(r.Success);
        var furn = r.Furniture!;
        Assert.Equal(2, furn.Count);
        Assert.All(furn, f => Assert.Equal("CHAIR", f.Category));
        // insertPath = 顶层 INSERT 句柄 + 子 INSERT 句柄
        Assert.All(furn, f => Assert.Equal(2, f.InsertPath.Count));
        Assert.All(furn, f => Assert.Equal(f.InsertPath, f.SourceHandles));
        Assert.Equal(2, furn.Select(f => f.FurnitureId).Distinct().Count());
        // 容器块 LAYOUT_GRP 本身不作为家具输出
        Assert.DoesNotContain(furn, f => f.BlockName == "LAYOUT_GRP");
        // 展开后世界坐标：容器插入点 (1000,1000)，CHAIR 局部 500×500
        Assert.Contains(furn, f => f.Position is { X: 1250, Y: 1250 });
        Assert.Contains(furn, f => f.Position is { X: 3250, Y: 1250 });
    }

    // ③ INSUNITS=厘米 → scaleToMm=10、HEADER、confirmed=true
    [Fact]
    public void UnitsCm_HeaderConfirmed()
    {
        var r = ParseFixture("units_cm.dxf");
        var u = Assert.IsType<UnitResolutionDto>(r.UnitResolution);
        Assert.Equal("CENTIMETER", u.RawUnit);
        Assert.Equal("CENTIMETER", u.DeclaredUnit);
        Assert.Equal("mm", u.CanonicalUnit);
        Assert.Equal(10.0, u.ScaleToMm);
        Assert.Equal("HEADER", u.ScaleSource);
        Assert.False(u.ScaleAssumed);
        Assert.True(u.Confirmed);
        Assert.Equal("high", u.Confidence);
        // 几何按厘米→毫米换算：2000 单位 = 20000mm
        Assert.Equal(20000, r.Furniture!.Single().GeometryWidthMm);
    }

    // ④ 无 INSUNITS → ASSUMED / confirmed=false + PARTIAL，保留 UNIT_ASSUMED
    [Fact]
    public void UnitsNone_AssumedUnconfirmed()
    {
        var r = ParseFixture("units_none.dxf");
        var u = Assert.IsType<UnitResolutionDto>(r.UnitResolution);
        Assert.Equal("UNITLESS", u.RawUnit);
        Assert.Null(u.DeclaredUnit);
        Assert.Equal(1.0, u.ScaleToMm);
        Assert.Equal("ASSUMED", u.ScaleSource);
        Assert.True(u.ScaleAssumed);
        Assert.False(u.Confirmed);
        Assert.Contains(r.QualityIssues, q => q.Code == "UNIT_ASSUMED");
        Assert.True(r.Success);
        Assert.Equal("PARTIAL", r.ParseStatus);
    }

    // ⑤ 无法分类的块 → UNKNOWN 保留，绝不丢弃
    [Fact]
    public void UnknownBlock_PreservedAsUnknown()
    {
        var r = ParseFixture("unknown_block.dxf");
        Assert.True(r.Success);
        var f = Assert.Single(r.Furniture!);
        Assert.Equal("UNKNOWN", f.Category);
        Assert.Equal("low", f.CategoryConfidence);
        Assert.Contains("UNKNOWN_CATEGORY", f.QualityFlags);
        // 家具全部 UNKNOWN → NEEDS_REVIEW（success 仍为 true）
        Assert.Equal("NEEDS_REVIEW", r.ParseStatus);
        Assert.Equal("NEEDS_REVIEW", r.Capabilities!["furniture"]);
    }

    // ⑥ parseStatus / success 兼容映射：FAILED ⟺ success=false
    [Fact]
    public void ParseStatus_SuccessMapping()
    {
        var ok = ParseFixture("two_inserts.dxf");
        Assert.True(ok.Success);
        Assert.NotEqual("FAILED", ok.ParseStatus);

        var corrupt = CadParsePipeline.Parse(new byte[] { 1, 2, 3, 4, 5 }, "bad.dwg");
        Assert.False(corrupt.Success);
        Assert.Equal("FAILED", corrupt.ParseStatus);
        Assert.NotNull(corrupt.ErrorCode);
    }

    // ⑦ 非家具块（门/标注）被排除，正常家具保留
    [Fact]
    public void NonFurnitureBlocks_Excluded()
    {
        var r = ParseFixture("nonfurniture.dxf");
        Assert.True(r.Success);
        var f = Assert.Single(r.Furniture!);
        Assert.Equal("SOFA", f.Category);
    }

    // ⑧ targetSceneCandidates 输出 + targetId 指定切换
    [Fact]
    public void TargetSceneCandidates_ManualSwitch()
    {
        var r = ParseFixture("multi_scene.dxf");
        Assert.True(r.Success);
        var candidates = Assert.IsType<List<TargetSceneCandidateDto>>(r.TargetSceneCandidates);
        Assert.Equal(2, candidates.Count);
        Assert.Single(candidates, c => c.Recommended);
        // 按 score 降序
        Assert.True(candidates[0].Score >= candidates[1].Score);
        Assert.All(candidates, c => Assert.StartsWith("SCENE-", c.TargetId));
        // AUTO 选择 = 推荐候选
        Assert.Equal("AUTO", r.TargetScene!.SelectionSource);
        Assert.Equal(candidates[0].TargetId, r.TargetScene.TargetId);
        // P0 不输出候选预览图
        Assert.All(candidates, c => Assert.NotNull(c.Bounds));

        // 手动切换到另一个候选
        var manual = ParseFixture("multi_scene.dxf", targetId: candidates[1].TargetId);
        Assert.True(manual.Success);
        Assert.Equal("MANUAL", manual.TargetScene!.SelectionSource);
        Assert.Equal(candidates[1].TargetId, manual.TargetScene.TargetId);

        // 不存在的 targetId → TARGET_NOT_FOUND（endpoint 映射 400）
        var bad = ParseFixture("multi_scene.dxf", targetId: "SCENE-deadbeef");
        Assert.False(bad.Success);
        Assert.Equal("TARGET_NOT_FOUND", bad.ErrorCode);
    }

    // ⑨ 资源守卫：超小 MaxExpandedEntities → ENTITY_LIMIT + PARTIAL，实例不丢弃
    [Fact]
    public void ResourceGuard_EntityLimit()
    {
        var r = ParseFixture("two_inserts.dxf", new ParserOptions { MaxExpandedEntities = 1 });
        Assert.True(r.Success);
        Assert.Contains(r.QualityIssues, q => q.Code == "ENTITY_LIMIT");
        Assert.Equal("PARTIAL", r.ParseStatus);
        // 未展开的实例仍保留候选（UNRESOLVED_BLOCK 标记）
        Assert.Equal(2, r.Furniture!.Count);
        Assert.Contains(r.Furniture, f => f.QualityFlags.Contains("UNRESOLVED_BLOCK"));
    }

    [Fact]
    public void ResourceGuard_BlockDepthLimit()
    {
        // 嵌套场景限制深度 1：顶层展开，子 INSERT 停止展开并标记 UNRESOLVED_BLOCK（不丢弃）
        var r = ParseFixture("nested_insert.dxf", new ParserOptions { MaxBlockDepth = 1 });
        Assert.True(r.Success);
        Assert.Contains(r.QualityIssues, q => q.Code == "BLOCK_EXPANSION_LIMIT");
        Assert.Equal(2, r.Furniture!.Count);
        Assert.All(r.Furniture, f => Assert.Contains("UNRESOLVED_BLOCK", f.QualityFlags));
    }

    // 房间稳定 ID：确定性排序编号
    [Fact]
    public void RoomIdAssigner_Deterministic()
    {
        var rooms = new List<RoomDto>
        {
            new() { Label = "主卧", AreaM2 = 20, LabelPoint = new PointDto { X = 5000, Y = 0 } },
            new() { Label = "客厅", AreaM2 = 40, LabelPoint = new PointDto { X = 1000, Y = 0 } },
        };
        RoomIdAssigner.Assign(rooms);
        Assert.Equal("ROOM-001", rooms[1].RoomId); // X 小者先编号
        Assert.Equal("ROOM-002", rooms[0].RoomId);
    }

    // 家具 → 房间归属：锚点 PIP + 轮廓重叠复核
    [Fact]
    public void RoomAssigner_AnchorAndOverlap()
    {
        var gf = NtsGeometryServices.Instance.CreateGeometryFactory();
        Polygon Rect(double x0, double y0, double x1, double y1) => gf.CreatePolygon(new[]
        {
            new Coordinate(x0, y0), new Coordinate(x1, y0),
            new Coordinate(x1, y1), new Coordinate(x0, y1), new Coordinate(x0, y0)
        });
        var rooms = new List<(string, Polygon)> { ("ROOM-001", Rect(0, 0, 4000, 3000)), ("ROOM-002", Rect(4000, 0, 8000, 3000)) };
        var target = new Envelope(-1000, 9000, -1000, 4000);

        // 锚点在 ROOM-001 内，轮廓基本在 ROOM-001 → 归属
        var (id1, flag1) = RoomAssigner.Assign(new Coordinate(1000, 1500), Rect(500, 1000, 1500, 2000), rooms, target);
        Assert.Equal("ROOM-001", id1);
        Assert.Null(flag1);

        // 锚点在 ROOM-001 但轮廓跨房间（ROOM-002 覆盖更多）→ CROSS_ROOM + null
        var (id2, flag2) = RoomAssigner.Assign(new Coordinate(3900, 1500), Rect(3500, 1000, 5500, 2000), rooms, target);
        Assert.Null(id2);
        Assert.Equal("CROSS_ROOM", flag2);

        // 锚点不在任何房间但在目标区内 → NO_ROOM_MATCH
        var (id3, flag3) = RoomAssigner.Assign(new Coordinate(8500, 1500), Rect(8200, 1000, 8800, 2000), rooms, target);
        Assert.Null(id3);
        Assert.Equal("NO_ROOM_MATCH", flag3);

        // 目标区外 → OUT_OF_TARGET_SCENE
        var (id4, flag4) = RoomAssigner.Assign(new Coordinate(20000, 1500), Rect(19800, 1000, 20200, 2000), rooms, target);
        Assert.Null(id4);
        Assert.Equal("OUT_OF_TARGET_SCENE", flag4);
    }

    // 新增契约字段存在性（旧字段语义不变的前提下全部为可选输出）
    [Fact]
    public void ContractFields_Present()
    {
        var r = ParseFixture("two_inserts.dxf");
        Assert.Equal("1.0", r.SceneVersion);
        Assert.Equal("1.3.0-p1", r.ParserVersion);
        Assert.NotNull(r.ParseStatus);
        Assert.NotNull(r.Capabilities);
        Assert.True(r.Capabilities!.ContainsKey("rooms"));
        Assert.True(r.Capabilities.ContainsKey("furniture"));
        Assert.NotNull(r.UnitResolution);
        Assert.NotNull(r.TargetScene);
        Assert.NotNull(r.TargetSceneCandidates);
        Assert.NotNull(r.Furniture);
    }
}
