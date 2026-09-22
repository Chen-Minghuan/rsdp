using NetTopologySuite.Geometries;

namespace RsdpCadParser.Domain;

/// <summary>扁平化后的实体几何类别。</summary>
public enum FlatKind
{
    Line,
    Polyline,
    Hatch,
    Text,
    Dimension,
    Other
}

/// <summary>
/// 从 CAD 实体抽取的扁平几何（坐标已统一换算为毫米）。
/// </summary>
public class FlatEntity
{
    public FlatKind Kind { get; set; }
    public string Layer { get; set; } = "";
    public Envelope BBox { get; set; } = new();

    /// <summary>Line/Polyline 顶点序列（bulge 圆弧已离散为点）。</summary>
    public List<Coordinate>? Points { get; set; }
    public bool Closed { get; set; }

    /// <summary>Hatch 边界环集合（每环为闭合点列，首尾点相同）。</summary>
    public List<List<Coordinate>>? Rings { get; set; }
    public bool HatchIsSolid { get; set; }
    public string HatchPattern { get; set; } = "";

    /// <summary>清洗后的文字内容（Text 实体）。</summary>
    public string? Text { get; set; }
    public Coordinate? TextPos { get; set; }

    /// <summary>Dimension 实体的测量值（毫米）。</summary>
    public double? Measure { get; set; }
}

/// <summary>图纸上的"面积: X㎡"文字标注（金样本校验基准来源）。</summary>
public record AreaAnnotation(double AreaM2, Coordinate Pos, string Layer, string RawText);

/// <summary>
/// 语义面积标注：面积数值 + 关联空间名（如"盥洗间 2.46㎡"、"客餐厅、厨房及过道 47.43㎡"）。
/// IsComposite = 名称含 、/及（多个空间的合并铺贴面积）。
/// </summary>
public record SemanticAnnotation(string Name, double AreaM2, Coordinate Pos, string Layer, bool IsComposite);
