using RsdpCadParser.Domain;
using RsdpCadParser.Parser;
using Xunit;
using Xunit.Abstractions;

namespace RsdpCadParser.Tests;

/// <summary>
/// 金样本回归。当前样本（2026-09-19 用户更新）：
/// - `户型图.dwg`：无标签/无面积标注的精简版（380 实体）——验证几何产出与 drawingBounds 对齐；
/// - `家具平面布局图.dwg`：同户型带 13 类房间标签版本（805 实体）——验证标签关联与 drawingBounds 对齐。
/// drawingBounds 语义：产出空间（rooms + unnamedRegions）多边形并集外包络 + OutputBoundsMarginMm（200mm），
/// 前端以此为图片叠加归一化基准；本测试强制其紧贴多边形并集，防叠加错位回归。
/// </summary>
public class GoldenFileTests
{
    private readonly ITestOutputHelper _output;
    public GoldenFileTests(ITestOutputHelper output) => _output = output;

    [Fact]
    public void Parse_GoldenDwg_GeometryAndBounds()
    {
        var detailed = LoadAndParse(GoldenFile.DefaultFileName);
        if (detailed == null) return;
        var result = detailed.Result;

        Assert.True(result.Success, $"解析失败: {result.ErrorCode} {result.ErrorMessage}");
        Assert.Equal("mm", result.Units);
        // 精简版无标签：全部空间进未命名清单
        _output.WriteLine($"命名 {result.Rooms.Count}，未命名 {result.UnnamedRegions.Count}");
        foreach (var u in result.UnnamedRegions)
            _output.WriteLine($"  [未命名] {u.AreaM2:F2}㎡");
        Assert.True(result.Rooms.Count + result.UnnamedRegions.Count >= 8, "空间总数 < 8");
        Assert.All(result.Rooms.Concat(result.UnnamedRegions.Select(u => new RoomDto { Polygon = u.Polygon })),
            r => Assert.True(r.Polygon.Length >= 4));
        AssertBoundsTight(result);
        AssertPreview(result);
        Assert.All(result.UnnamedRegions, region =>
        {
            Assert.True(region.WidthMm > 0 && region.DepthMm > 0);
            Assert.NotNull(region.LabelPoint);
        });
    }

    [Fact]
    public void Parse_FurnitureLayoutDwg_LabelsAndBounds()
    {
        var detailed = LoadAndParse("家具平面布局图.dwg");
        if (detailed == null) return;
        var result = detailed.Result;

        Assert.True(result.Success, $"解析失败: {result.ErrorCode} {result.ErrorMessage}");
        _output.WriteLine($"命名房间 {result.Rooms.Count}，未命名空间 {result.UnnamedRegions.Count}:");
        foreach (var r in result.Rooms)
            _output.WriteLine($"  {r.Label}({r.RoomType}) {r.AreaM2,7:F2}㎡ conf={r.Confidence} 成员={string.Join("+", r.MemberLabels ?? new List<string>())}");
        foreach (var u in result.UnnamedRegions)
            _output.WriteLine($"  [未命名] {u.AreaM2:F2}㎡ hint={u.Hint}");

        // 命名房间 ≥9，空间总数（含未命名）≥11
        Assert.True(result.Rooms.Count >= 9, $"命名房间 {result.Rooms.Count} < 9");
        Assert.True(result.Rooms.Count + result.UnnamedRegions.Count >= 11, "空间总数 < 11");

        // 13 类空间名全部命中（label 或 memberLabels；小孩房/儿童房视为同类）
        var allNames = result.Rooms.Where(r => r.Label != null).Select(r => r.Label!)
            .Concat(result.Rooms.SelectMany(r => r.MemberLabels ?? new List<string>()))
            .ToList();
        _output.WriteLine($"命中的空间名: {string.Join("、", allNames.Distinct())}");
        (string Name, string[] Alias)[] expected =
        {
            ("客厅", new[] { "客厅" }), ("餐厅", new[] { "餐厅" }), ("玄关", new[] { "玄关" }),
            ("厨房", new[] { "厨房" }), ("主卧", new[] { "主卧" }), ("衣帽间", new[] { "衣帽间" }),
            ("次卧", new[] { "次卧" }), ("小孩房", new[] { "小孩房", "儿童房" }), ("书房", new[] { "书房" }),
            ("主卫", new[] { "主卫" }), ("公卫", new[] { "公卫" }),
            ("生活阳台", new[] { "生活阳台" }), ("休闲阳台", new[] { "休闲阳台" }),
        };
        var missing = expected.Where(e => !allNames.Any(n => e.Alias.Any(n.Contains))).Select(e => e.Name).ToList();
        Assert.True(missing.Count == 0, $"空间名缺失: {string.Join("、", missing)}");

        // roomType 映射抽查
        Assert.Equal("LIVING_ROOM", result.Rooms.First(r => r.Label == "客厅").RoomType);
        Assert.Equal("BEDROOM", result.Rooms.First(r => r.Label == "主卧").RoomType);
        Assert.Equal("BATHROOM", result.Rooms.First(r => r.Label == "主卫").RoomType);
        Assert.All(result.Rooms, r =>
        {
            Assert.True(r.Polygon.Length >= 4);
            Assert.True(r.AreaM2 > 0);
            Assert.Equal("cad_geometry", r.DimensionSource);
        });
        AssertBoundsTight(result);
        AssertPreview(result);

        // 面积标注匹配（该副本无标注时跳过；若将来放回带标注样本则恢复 ≥8/10 断言）
        var annotations = detailed.AreaAnnotations;
        if (annotations.Count >= 8)
        {
            int matched = annotations.Count(a =>
            {
                var best = result.Rooms.OrderBy(r => Math.Abs(r.AreaM2 - a.AreaM2)).FirstOrDefault();
                return best != null && Math.Abs(best.AreaM2 - a.AreaM2) / a.AreaM2 <= 0.03;
            });
            _output.WriteLine($"面积标注匹配率: {matched}/{annotations.Count}");
            Assert.True(matched >= 8, $"面积匹配率回归：{matched}/{annotations.Count} < 8/10");
        }
    }

    /// <summary>drawingBounds 必须紧贴产出空间多边形并集（边距 ≤ OutputBoundsMarginMm + 1mm 舍入）。</summary>
    private void AssertBoundsTight(CadParseResult result)
    {
        var opt = new ParserOptions();
        var b = result.DrawingBounds!;
        var allPts = result.Rooms.SelectMany(r => r.Polygon)
            .Concat(result.UnnamedRegions.SelectMany(u => u.Polygon)).ToList();
        Assert.NotEmpty(allPts);
        double polyMinX = allPts.Min(p => p[0]), polyMaxX = allPts.Max(p => p[0]);
        double polyMinY = allPts.Min(p => p[1]), polyMaxY = allPts.Max(p => p[1]);
        _output.WriteLine($"drawingBounds: ({b.MinX},{b.MinY})~({b.MaxX},{b.MaxY})；多边形并集: ({polyMinX:F1},{polyMinY:F1})~({polyMaxX:F1},{polyMaxY:F1})");
        double maxSlack = opt.OutputBoundsMarginMm + 1;
        Assert.True(Math.Abs(b.MinX - polyMinX) <= maxSlack && Math.Abs(b.MaxX - polyMaxX) <= maxSlack
                    && Math.Abs(b.MinY - polyMinY) <= maxSlack && Math.Abs(b.MaxY - polyMaxY) <= maxSlack,
            $"drawingBounds 未紧贴多边形并集（边距配置 {opt.OutputBoundsMarginMm}mm）");
    }

    /// <summary>规范预览必须是合法 PNG，并与 drawingBounds 使用完全相同的坐标范围。</summary>
    private static void AssertPreview(CadParseResult result)
    {
        var preview = Assert.IsType<PreviewDto>(result.Preview);
        Assert.True(preview.Width > 0 && preview.Height > 0);
        Assert.NotNull(preview.Bounds);
        Assert.Equal(result.DrawingBounds!.MinX, preview.Bounds!.MinX);
        Assert.Equal(result.DrawingBounds.MinY, preview.Bounds.MinY);
        Assert.Equal(result.DrawingBounds.MaxX, preview.Bounds.MaxX);
        Assert.Equal(result.DrawingBounds.MaxY, preview.Bounds.MaxY);
        var png = Convert.FromBase64String(preview.PngBase64!);
        Assert.True(png.Length > 100);
        Assert.Equal(new byte[] { 137, 80, 78, 71, 13, 10, 26, 10 }, png.Take(8).ToArray());
    }

    private CadParsePipeline.DetailedResult? LoadAndParse(string fileName)
    {
        var path = GoldenFile.Locate(fileName);
        if (path == null)
        {
            _output.WriteLine($"SKIP: 金样本文件 {fileName} 不存在（可设 CAD_GOLDEN_DWG 指向 户型图.dwg）");
            return null;
        }
        _output.WriteLine($"金样本: {path}");
        var detailed = CadParsePipeline.ParseDetailed(File.ReadAllBytes(path), Path.GetFileName(path));
        foreach (var d in detailed.Diagnostics) _output.WriteLine("[diag] " + d);
        return detailed;
    }
}
