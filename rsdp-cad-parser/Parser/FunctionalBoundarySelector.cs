using NetTopologySuite;
using NetTopologySuite.Geometries;
using NetTopologySuite.Operation.Polygonize;
using NetTopologySuite.Operation.Union;
using RsdpCadParser.Domain;

namespace RsdpCadParser.Parser;

/// <summary>固定柜体的外轮廓可作为待复核的功能分隔，不作为建筑墙体。</summary>
internal static class FunctionalBoundarySelector
{
    private static readonly GeometryFactory Factory = NtsGeometryServices.Instance.CreateGeometryFactory();

    /// <summary>围合柜体线网并合并相邻格子，保留外边界，去除柜内格线及植物圆形图案。</summary>
    internal static List<FlatEntity> Select(IEnumerable<FlatEntity> entities, Envelope region)
    {
        var lines = new List<Geometry>();
        foreach (var e in entities.Where(e => e.Layer.Contains("固定家具")
                     && e.Kind is FlatKind.Line or FlatKind.Polyline
                     && e.Points?.Count >= 2 && e.BBox.Intersects(region)))
        {
            var points = e.Points!;
            int count = e.Closed ? points.Count : points.Count-1;
            for(int i=0;i<count;i++)
            {
                var a=points[i];var b=points[(i+1)%points.Count];
                if(a.Distance(b)<50)continue;
                // 柜体结构以直角边线为证据；圆形植物/图案不进入分隔线网。
                if(Math.Abs(a.X-b.X)>1 && Math.Abs(a.Y-b.Y)>1)continue;
                lines.Add(Factory.CreateLineString(new[]{a.Copy(),b.Copy()}));
            }
        }
        if(lines.Count==0)return new();
        var polygonizer=new Polygonizer();
        polygonizer.Add(UnaryUnionOp.Union(lines));
        var cells=polygonizer.GetPolygons().Cast<Polygon>().Where(p=>p.IsValid && p.Area>=10_000).ToList();
        if(cells.Count==0)return new();
        return WallExtractor.Flatten(UnaryUnionOp.Union(cells.Cast<Geometry>().ToList()))
            .Where(p=>p.Area>=100_000).Select(p=>new FlatEntity
            {
                Kind=FlatKind.Polyline, Layer="功能分隔（固定柜体）", Closed=true,
                Points=p.ExteriorRing.Coordinates.ToList(), BBox=p.EnvelopeInternal
            }).ToList();
    }
}
