using NetTopologySuite.Geometries;

namespace RsdpCadParser.Parser;

/// <summary>用门扇与四分之一开启弧恢复门洞阈线；开启弧不作为空间隔墙。</summary>
internal static class DoorBoundaryReconstructor
{
    /// <summary>开启弧不参与房间围合；仅当门轴和某个弧端都有墙线支撑时补入门洞阈线。</summary>
    internal static int Apply(WallExtractor.WallExtractionResult walls)
    {
        var replacements = new List<(List<Coordinate> Arc, List<Coordinate>? Leaf, List<Coordinate>? Threshold)>();
        foreach (var arc in walls.DoorLines.Where(p => p.Count >= 6))
        {
            var a = arc[0]; var b = arc[arc.Count / 2]; var c = arc[^1];
            // 以局部坐标求圆心，避免大 CAD 坐标的平方消减。
            double bx=b.X-a.X, by=b.Y-a.Y, cx=c.X-a.X, cy=c.Y-a.Y;
            double determinant=2*(bx*cy-by*cx);
            if (Math.Abs(determinant)<1e-6) continue;
            double q=bx*bx+by*by, r=cx*cx+cy*cy;
            var center=new Coordinate(a.X+(q*cy-r*by)/determinant,a.Y+(bx*r-cx*q)/determinant);
            double radius=center.Distance(a);
            if (radius<450 || radius>1600 || arc.Any(p=>Math.Abs(center.Distance(p)-radius)>5)) continue;
            double dot=((a.X-center.X)*(c.X-center.X)+(a.Y-center.Y)*(c.Y-center.Y))/(radius*radius);
            if (Math.Abs(dot)>0.26) continue; // 约 75~105° 的门开启弧。
            var leaf=walls.DoorLines.FirstOrDefault(p=>p.Count==2 &&
                ((p[0].Distance(center)<40 && (p[1].Distance(a)<40 || p[1].Distance(c)<40)) ||
                 (p[1].Distance(center)<40 && (p[0].Distance(a)<40 || p[0].Distance(c)<40))));
            if (leaf==null)
            {
                replacements.Add((arc,null,null));
                continue;
            }
            var leafTip=leaf[0].Distance(center)>leaf[1].Distance(center)?leaf[0]:leaf[1];
            var alternateTip=leafTip.Distance(a)<leafTip.Distance(c)?c:a;
            bool Supported(Coordinate p)=>walls.WallLines.Any(line=>line.Any(v=>v.Distance(p)<=300));
            var thresholdTip = Supported(alternateTip) ? alternateTip
                : Supported(leafTip) ? leafTip : null;
            replacements.Add((arc,leaf,Supported(center) && thresholdTip != null
                ? new List<Coordinate>{center,thresholdTip.Copy()} : null));
        }
        foreach(var replacement in replacements)
        {
            walls.DoorLines.Remove(replacement.Arc);
            if (replacement.Leaf != null) walls.DoorLines.Remove(replacement.Leaf);
            if (replacement.Threshold != null) walls.DoorLines.Add(replacement.Threshold);
        }
        return replacements.Count(replacement => replacement.Threshold != null);
    }
}
