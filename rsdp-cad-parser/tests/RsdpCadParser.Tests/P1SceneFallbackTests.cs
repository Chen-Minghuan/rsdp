using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;

namespace RsdpCadParser.Tests;

/// <summary>
/// P1 Scene-aware Block Fallback 契约测试（块角色分类 / 方案级容器剔除 /
/// 块展开建筑几何回退 / 堆叠标签降级 / 未命名空间 roomId 参与家具归属）。
/// fixture 为手写/生成最小 ASCII DXF（tests/RsdpCadParser.Tests/Fixtures/）。
/// </summary>
public class P1SceneFallbackTests
{
    private static string FixturePath(string name)
        => Path.Combine(AppContext.BaseDirectory, "Fixtures", name);

    private static CadParseResult ParseFixture(string name, ParserOptions? opt = null, string? targetId = null)
        => CadParsePipeline.Parse(File.ReadAllBytes(FixturePath(name)), name, opt, targetId);

    // ① 方案级容器块（跨度 >8m 且图元 ≥100）不作为家具输出；正常家具保留
    [Fact]
    public void SceneContainer_ExcludedFromFurniture()
    {
        var r = ParseFixture("scene_container.dxf");
        Assert.True(r.Success, $"解析失败: {r.ErrorCode} {r.ErrorMessage}");
        var f = Assert.Single(r.Furniture!);
        Assert.Equal("SOFA", f.Category);
        Assert.Equal("SOFA_3P", f.BlockName);
        // 容器块 SCHEME_A 不下场：无任何家具候选引用它
        Assert.DoesNotContain(r.Furniture!, x => x.BlockName == "SCHEME_A");
        // 无超大占地（容器 10000×10000 若误留将远超家具上限）
        Assert.All(r.Furniture!, x => Assert.True(x.GeometryWidthMm <= 8000 && x.GeometryDepthMm <= 8000));
    }

    // ② 直接实体无墙线时，回退用块展开建筑几何围合空间；块内标签正常指派
    [Fact]
    public void BlockGeometryFallback_ProducesRoom()
    {
        var r = ParseFixture("block_fallback.dxf");
        Assert.True(r.Success, $"解析失败: {r.ErrorCode} {r.ErrorMessage}");
        Assert.Contains(r.QualityIssues, q => q.Code == "ROOMS_FROM_BLOCK_FALLBACK");
        // 两个房间均由块展开墙线围合，标签（直接实体文字）正常指派
        Assert.Equal(2, r.Rooms.Count);
        var living = Assert.Single(r.Rooms, x => x.Label == "客厅");
        Assert.Equal("LIVING_ROOM", living.RoomType);
        Assert.True(living.AreaM2 is > 5 and < 15, $"客厅面积异常: {living.AreaM2}");
        Assert.Single(r.Rooms, x => x.Label == "主卧");
        Assert.All(r.Rooms, x => Assert.False(string.IsNullOrEmpty(x.RoomId)));
        // 回退产出参与能力评估：rooms 非 NEEDS_REVIEW
        Assert.NotEqual("NEEDS_REVIEW", r.Capabilities!["rooms"]);
    }

    // ③ 回退不触发时行为不变：主路径有产出即不进入回退
    [Fact]
    public void BlockGeometryFallback_NotTriggeredWhenMainPathWorks()
    {
        var r = ParseFixture("stacked_labels.dxf");
        Assert.True(r.Success);
        Assert.DoesNotContain(r.QualityIssues, q => q.Code == "ROOMS_FROM_BLOCK_FALLBACK");
    }

    // ④ 堆叠标签降级：同一位置 ≥3 个房间名不挂房间，降级为候选名 hint
    [Fact]
    public void StackedLabels_QuarantinedToCandidates()
    {
        var r = ParseFixture("stacked_labels.dxf");
        Assert.True(r.Success, $"解析失败: {r.ErrorCode} {r.ErrorMessage}");
        Assert.Contains(r.QualityIssues, q => q.Code == "LABELS_STACKED");
        // 堆叠标签（客厅/主卧/厨房）不得成为任何房间的 label
        Assert.DoesNotContain(r.Rooms, x => x.Label is "客厅" or "主卧" or "厨房");
        // 正常放置的标签正常指派
        Assert.Contains(r.Rooms, x => x.Label == "卫生间");
        // 堆叠点所在空间以未命名输出且带候选名 hint（另有墙条碎片空间，按 hint 定位）
        var region = Assert.Single(r.UnnamedRegions, x => x.Hint != null && x.Hint.Contains("候选名"));
        Assert.Contains("客厅", region.Hint!);
        Assert.Equal("PARTIAL", r.Capabilities!["rooms"]);
    }

    // ⑤ 未命名空间也分配稳定 roomId 且参与家具归属（P1：空间内逐件家具）
    [Fact]
    public void UnnamedRegion_HasRoomId_AndFurnitureAssigned()
    {
        var r = ParseFixture("stacked_labels.dxf");
        Assert.True(r.Success);
        // 堆叠点所在未命名空间（沙发所在的 A 房）
        var region = Assert.Single(r.UnnamedRegions, x => x.Hint != null && x.Hint.Contains("候选名"));
        Assert.False(string.IsNullOrEmpty(region.RoomId));
        Assert.StartsWith("ROOM-", region.RoomId);
        // 全部空间（命名+未命名）roomId 互不冲突
        var allIds = r.Rooms.Select(x => x.RoomId).Concat(r.UnnamedRegions.Select(x => x.RoomId)).ToList();
        Assert.Equal(allIds.Count, allIds.Distinct().Count());
        // 沙发锚点落在未命名空间内 → roomId 指向它
        var sofa = Assert.Single(r.Furniture!);
        Assert.Equal("SOFA", sofa.Category);
        Assert.Equal(region.RoomId, sofa.RoomId);

        // 重复解析：未命名空间 ID 稳定可重现
        var r2 = ParseFixture("stacked_labels.dxf");
        Assert.Equal(
            r.UnnamedRegions.Select(x => x.RoomId).OrderBy(x => x).ToArray(),
            r2.UnnamedRegions.Select(x => x.RoomId).OrderBy(x => x).ToArray());
    }

    // ⑥ P1-A 候选清洗：碎件（最大边 <100mm）标记 REJECTED + FURNITURE_SIZE_TOO_SMALL，不删除
    [Fact]
    public void CandidateHygiene_TinyMarkedRejected()
    {
        var r = ParseFixture("dup_stack.dxf");
        Assert.True(r.Success);
        var pin = Assert.Single(r.Furniture!, f => f.BlockName == "PIN");
        Assert.Equal("REJECTED", pin.CandidateStatus);
        Assert.Contains("FURNITURE_SIZE_TOO_SMALL", pin.QualityFlags);
        // 正常尺寸候选不受影响
        Assert.All(r.Furniture!.Where(f => f.BlockName == "SOFA_3P"),
            f => Assert.Equal("VALID", f.CandidateStatus));
    }

    // ⑦ P1-B 去重分组：同位置同几何副本成组（PRIMARY/DUPLICATE），不同位置不算重复
    [Fact]
    public void DuplicateGroup_SamePositionMerged_NotDeleted()
    {
        var r = ParseFixture("dup_stack.dxf");
        Assert.True(r.Success);
        var sofas = r.Furniture!.Where(f => f.BlockName == "SOFA_3P").ToList();
        Assert.Equal(3, sofas.Count); // 不物理删除

        var grouped = sofas.Where(f => f.DuplicateGroupId != null).ToList();
        Assert.Equal(2, grouped.Count);
        Assert.Single(grouped, f => f.DuplicateStatus == "PRIMARY");
        Assert.Single(grouped, f => f.DuplicateStatus == "DUPLICATE");
        Assert.All(grouped, f => Assert.Equal(2, f.DuplicateCount));
        Assert.All(grouped, f => Assert.Equal(2, f.SourceInstances!.Count));
        Assert.StartsWith("DG-", grouped[0].DuplicateGroupId!);
        // 不同位置的实例不成组
        var lone = Assert.Single(sofas, f => f.DuplicateGroupId == null);
        Assert.Equal(6000 + 1000, lone.Position!.X); // 插入点 6000 + 块内中心 1000

        // 重复解析：分组结果稳定可重现
        var r2 = ParseFixture("dup_stack.dxf");
        var g2 = r2.Furniture!.Where(f => f.DuplicateGroupId != null).ToList();
        Assert.Equal(grouped.Select(f => f.FurnitureId).OrderBy(x => x).ToArray(),
            g2.Select(f => f.FurnitureId).OrderBy(x => x).ToArray());
        Assert.Equal(grouped[0].DuplicateGroupId, g2[0].DuplicateGroupId);
    }

    // ⑧ P1-C 视觉语义分组：同块同尺寸跨位置共享 visionGroupId；REJECTED 不分组；
    //    EmitIsolationImages 输出各组白底线稿 PNG
    [Fact]
    public void VisionGroup_SharedAcrossPositions_IsolationRendered()
    {
        var r = ParseFixture("dup_stack.dxf", new ParserOptions { EmitIsolationImages = true });
        Assert.True(r.Success);
        var sofas = r.Furniture!.Where(f => f.BlockName == "SOFA_3P").ToList();
        Assert.Single(sofas.Select(f => f.VisionGroupId).Distinct()); // 3 个位置实例共享同一视觉分组
        Assert.All(sofas, f => Assert.StartsWith("VG-", f.VisionGroupId!));
        // 碎件 REJECTED 不参与视觉分组
        Assert.Null(Assert.Single(r.Furniture!, f => f.BlockName == "PIN").VisionGroupId);
        // isolation 视图：一个分组一张 PNG
        var images = Assert.IsType<Dictionary<string, string>>(r.IsolationImages);
        var vg = sofas[0].VisionGroupId!;
        Assert.True(images.ContainsKey(vg));
        var png = Convert.FromBase64String(images[vg]);
        Assert.Equal((byte)0x89, png[0]); // PNG 魔数
        Assert.Equal((byte)'P', png[1]);
    }

    // ⑨ P1-E 栅格空间重建：2500mm 落地窗大口（超矢量门洞闭合上限 1800mm）+ 双线断口墙
    //    矢量路径围合失败 → 栅格膨胀封洞 → 泛洪分房 → 标签正常指派
    [Fact]
    public void RasterRebuild_SealsLargeOpenings_ProducesRooms()
    {
        // 大口 2500mm，矢量闭合上限 1800mm 必然失败；栅格膨胀 1300mm 可封
        var r = ParseFixture("raster_rooms.dxf", new ParserOptions { RasterWallDilateMm = 1300 });
        Assert.True(r.Success, $"解析失败: {r.ErrorCode} {r.ErrorMessage}");
        Assert.Contains(r.QualityIssues, q => q.Code == "ROOMS_FROM_RASTER_REBUILD");
        Assert.Equal("NEEDS_REVIEW", r.ParseStatus);
        Assert.Equal("NEEDS_REVIEW", r.Capabilities!["rooms"]);
        Assert.Equal(2, r.Rooms.Count);
        var living = Assert.Single(r.Rooms, x => x.Label == "客厅");
        var bedroom = Assert.Single(r.Rooms, x => x.Label == "主卧");
        // 理论内净面积 20.72㎡；栅格分辨率 50mm/px + 简化有偏差，±20% 容差
        Assert.All(r.Rooms, x => Assert.True(x.AreaM2 is > 15 and < 25, $"面积异常: {x.Label} {x.AreaM2}㎡"));
        Assert.All(r.Rooms, x => Assert.Equal("cad_geometry", x.DimensionSource));
        Assert.All(r.Rooms, x => Assert.False(string.IsNullOrEmpty(x.RoomId)));
        // 房间多边形互不重叠（分水岭限深回胀）
        Assert.NotEqual(living.RoomId, bedroom.RoomId);
    }

    // ⑩ P1-E 默认关闭栅格重建时（开关验证）不产生重建空间
    [Fact]
    public void RasterRebuild_DisabledByOption()
    {
        var r = ParseFixture("raster_rooms.dxf",
            new ParserOptions { RasterWallDilateMm = 1300, RasterRebuildEnabled = false });
        Assert.Empty(r.Rooms);
        Assert.DoesNotContain(r.QualityIssues, q => q.Code == "ROOMS_FROM_RASTER_REBUILD");
    }
}
