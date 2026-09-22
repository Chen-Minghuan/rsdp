using ACadSharp.Entities;
using RsdpCadParser.Parser;

namespace RsdpCadParser;

/// <summary>原始结构诊断：SOLID HATCH 边界原始数据 + 标签坐标 + 图层构成。</summary>
public static class ProbeRaw
{
    public static void Run(string path)
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        var doc = CadDocumentLoader.Load(File.ReadAllBytes(path), Path.GetFileName(path));

        // 1) 前 3 个 SOLID HATCH 的原始边界结构
        Console.WriteLine("===== SOLID HATCH 原始结构（前 3 个） =====");
        int shown = 0;
        foreach (var e in doc.ModelSpace.Entities)
        {
            if (e is not Hatch h || !h.IsSolid) continue;
            Console.WriteLine($"HATCH [{h.Layer.Name}] pattern={h.Pattern?.Name} paths={h.Paths.Count} bbox={FmtBB(h)}");
            foreach (var p in h.Paths.Take(4))
            {
                Console.WriteLine($"  path flags={p.Flags} isPolyline={p.IsPolyline} edges={p.Edges.Count}");
                foreach (var edge in p.Edges.Take(6))
                {
                    switch (edge)
                    {
                        case Hatch.BoundaryPath.Polyline pl:
                            Console.WriteLine($"    polyline verts={pl.Vertices.Count} closed={pl.IsClosed} first={string.Join(";", pl.Vertices.Take(4).Select(v => $"({v.X:F0},{v.Y:F0})"))}");
                            break;
                        case Hatch.BoundaryPath.Line ln:
                            Console.WriteLine($"    line ({ln.Start.X:F0},{ln.Start.Y:F0})→({ln.End.X:F0},{ln.End.Y:F0})");
                            break;
                        case Hatch.BoundaryPath.Arc arc:
                            Console.WriteLine($"    arc c=({arc.Center.X:F0},{arc.Center.Y:F0}) r={arc.Radius:F0} {arc.StartAngle:F2}→{arc.EndAngle:F2} ccw={arc.CounterClockWise}");
                            break;
                        default:
                            Console.WriteLine($"    {edge.GetType().Name}");
                            break;
                    }
                }
            }
            if (++shown >= 3) break;
        }

        // 2) 房间名标签坐标分布（判断子图位置关系）
        Console.WriteLine("\n===== 房间名标签坐标（客厅/主卧/厨房/玄关） =====");
        var labelRe = new System.Text.RegularExpressions.Regex("^(客厅|主卧|厨房|玄关|餐厅|主卫)$");
        foreach (var e in doc.ModelSpace.Entities)
        {
            if (e is MText mt)
            {
                var t = MTextCleaner.Clean(mt.PlainText ?? mt.Value);
                if (labelRe.IsMatch(t))
                    Console.WriteLine($"  \"{t}\" [{mt.Layer.Name}] @({mt.InsertPoint.X / 1000:F1},{mt.InsertPoint.Y / 1000:F1})");
            }
        }

        Console.WriteLine("\n===== 全部文字实体 =====");
        foreach (var e in doc.ModelSpace.Entities)
        {
            string? text = e switch
            {
                MText mt => MTextCleaner.Clean(mt.PlainText ?? mt.Value),
                TextEntity t => MTextCleaner.Clean(t.Value),
                _ => null
            };
            if (!string.IsNullOrWhiteSpace(text))
                Console.WriteLine($"  [{e.Layer.Name}] {e.ObjectType}: \"{text}\"");
        }

        // 3) 地面铺贴图图层构成
        Console.WriteLine("\n===== 地面铺贴图图层实体构成 =====");
        foreach (var g in doc.ModelSpace.Entities.Where(e => e.Layer.Name == "地面铺贴图").GroupBy(e => e.ObjectType.ToString()).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  {g.Key} ×{g.Count()}");

        // 4) 0 层实体构成
        Console.WriteLine("\n===== 0 层实体构成 =====");
        foreach (var g in doc.ModelSpace.Entities.Where(e => e.Layer.Name == "0").GroupBy(e => e.ObjectType.ToString()).OrderByDescending(g => g.Count()))
            Console.WriteLine($"  {g.Key} ×{g.Count()}");
    }

    private static string FmtBB(Entity e)
    {
        try
        {
            var b = e.GetBoundingBox();
            return $"({b.Min.X / 1000:F1},{b.Min.Y / 1000:F1})~({b.Max.X / 1000:F1},{b.Max.Y / 1000:F1})";
        }
        catch { return "?"; }
    }
}
