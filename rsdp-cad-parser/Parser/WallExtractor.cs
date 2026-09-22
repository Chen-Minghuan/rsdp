using System.Text.RegularExpressions;
using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>
/// HATCH 驱动墙体提取（spike/实测结论：墙体在 0 层、以材质填充图案表示，"墙体"命名图层为空；
/// 金样本墙体 = 0 层单个 ANSI31 HATCH 的 10 个边界环；SOLID 小填充是家具/门垛而非墙）。
/// 判定规则（有序）：
///   1. 排除图层（天花/铺贴/家具/灯具…）上的填充一律非墙；
///   2. 墙图层（^0$/墙/柱/wall…）上的 HATCH 一律为墙候选；
///   3. 其他图层：材质图案命中（ANSI31/AR-CONC/…）或实心填充才为墙候选；
///   4. 单个环面积 &lt; MinHatchRingAreaM2 的视为门垛/五金小填充，丢弃。
/// 兜底：无墙 HATCH 时输出墙图层命中的 LINE/LWPOLYLINE 供线网多边形化。
/// </summary>
public static class WallExtractor
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    public class WallExtractionResult
    {
        /// <summary>墙体实心区域并集（NTS 几何，毫米）。null 表示无 HATCH 墙体，需走线网兜底。</summary>
        public Geometry? WallUnion { get; set; }
        public int HatchCount { get; set; }
        public int SkippedHatchCount { get; set; }
        /// <summary>兜底墙线（墙图层命中的线/多段线点列）。</summary>
        public List<List<Coordinate>> WallLines { get; } = new();
        /// <summary>门图层线（门扇/门洞线，含弧离散点列）。</summary>
        public List<List<Coordinate>> DoorLines { get; } = new();
        /// <summary>门槛石/挡水条实心（铺贴/地面图层 HATCH 多边形）。</summary>
        public List<Geometry> SillSolids { get; } = new();
    }

    public static WallExtractionResult Extract(List<FlatEntity> targetEntities, ParserOptions opt, List<string>? diagnostics = null)
    {
        var result = new WallExtractionResult();
        var wallLayerRe = new Regex(opt.WallLayerPattern);
        var doorLayerRe = new Regex(opt.DoorLayerPattern);
        var materialRe = new Regex(opt.WallHatchPattern);
        var excludeRe = new Regex(opt.ExcludeWallLayerPattern);
        var sillLayerRe = new Regex(opt.SillLayerPattern);

        var wallPolygons = new List<Polygon>();
        foreach (var e in targetEntities)
        {
            if (e.Kind == FlatKind.Hatch)
            {
                // 铺贴/地面图层 HATCH = 门槛石/挡水条实心（不参与墙体，供铺贴归属减除）
                if (sillLayerRe.IsMatch(e.Layer))
                {
                    result.SillSolids.AddRange(HatchToPolygons(e, opt));
                    continue;
                }
                bool isWall = !excludeRe.IsMatch(e.Layer)
                              && (wallLayerRe.IsMatch(e.Layer)
                                  || materialRe.IsMatch(e.HatchPattern)
                                  || e.HatchIsSolid);
                if (!isWall) { result.SkippedHatchCount++; continue; }

                var polys = HatchToPolygons(e, opt);
                if (polys.Count == 0) { result.SkippedHatchCount++; continue; }
                result.HatchCount++;
                wallPolygons.AddRange(polys);
            }
            else if ((e.Kind == FlatKind.Line || e.Kind == FlatKind.Polyline)
                     && e.Points is { Count: >= 2 })
            {
                if (doorLayerRe.IsMatch(e.Layer))
                    result.DoorLines.Add(e.Points);
                else if (wallLayerRe.IsMatch(e.Layer) && !excludeRe.IsMatch(e.Layer))
                    result.WallLines.Add(e.Points);
            }
        }

        if (wallPolygons.Count > 0)
        {
            var valid = wallPolygons.Where(p => p.IsValid && !p.IsEmpty).ToList();
            int invalid = 0;
            foreach (var p in wallPolygons.Where(p => !p.IsValid))
            {
                try
                {
                    var fixedPolys = Flatten(p.Buffer(0)).ToList();
                    if (fixedPolys.Count > 0) valid.AddRange(fixedPolys);
                    else invalid++;
                }
                catch { invalid++; }
            }
            diagnostics?.Add($"墙体 HATCH：{result.HatchCount} 个命中 → {valid.Count} 个有效多边形"
                             + (invalid > 0 ? $"，{invalid} 个无效环被丢弃" : "")
                             + (result.SkippedHatchCount > 0 ? $"；跳过非墙填充 {result.SkippedHatchCount} 个" : ""));
            result.WallUnion = UnaryUnionOp.Union(valid.Cast<Geometry>().ToList());
        }
        else
        {
            diagnostics?.Add("目标子图内无 HATCH 墙体，转入墙线图层兜底（线网多边形化）");
        }
        return result;
    }

    /// <summary>
    /// HATCH 边界环 → 多边形集合。外环/孔洞按包含关系配对：
    /// 环按面积降序，被其他环包含的环作为最小容器的孔洞。
    /// 小于 MinHatchRingAreaM2 的壳环丢弃。
    /// </summary>
    private static List<Polygon> HatchToPolygons(FlatEntity hatch, ParserOptions opt)
    {
        var rings = new List<(LinearRing Ring, double Area)>();
        foreach (var r in hatch.Rings!)
        {
            try
            {
                var ring = Factory.CreateLinearRing(r.ToArray());
                var poly = Factory.CreatePolygon(ring);
                double area = poly.Area;
                if (area < opt.MinHatchRingAreaM2 * 1e6) continue; // 退化/小填充环
                if (opt.MaxSingleHatchAreaM2 > 0 && area / 1e6 > opt.MaxSingleHatchAreaM2) continue;
                rings.Add((ring, area));
            }
            catch { /* 非法环跳过 */ }
        }
        if (rings.Count == 0) return new List<Polygon>();

        var sorted = rings.OrderByDescending(r => r.Area).ToList();
        var shells = new List<(LinearRing Ring, double Area, List<LinearRing> Holes)>();
        foreach (var (ring, area) in sorted)
        {
            // 找最小包含壳
            (LinearRing Ring, double Area, List<LinearRing> Holes)? container = null;
            foreach (var s in shells)
            {
                if (s.Area > area && Factory.CreatePolygon(s.Ring).Contains(Factory.CreatePoint(ring.Coordinate)))
                    container = s;
            }
            if (container != null) container.Value.Holes.Add(ring);
            else shells.Add((ring, area, new List<LinearRing>()));
        }

        var polys = new List<Polygon>();
        foreach (var (ring, _, holes) in shells)
        {
            try
            {
                polys.Add(Factory.CreatePolygon(ring, holes.ToArray()));
            }
            catch
            {
                try { polys.Add(Factory.CreatePolygon(ring)); } catch { /* 丢弃 */ }
            }
        }
        return polys;
    }

    public static IEnumerable<Polygon> Flatten(Geometry g)
    {
        switch (g)
        {
            case Polygon p when !p.IsEmpty:
                yield return p;
                break;
            case GeometryCollection gc:
                foreach (var child in gc.Geometries)
                    foreach (var p in Flatten(child))
                        yield return p;
                break;
        }
    }
}
