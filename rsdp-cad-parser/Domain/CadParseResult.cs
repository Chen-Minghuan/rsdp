namespace RsdpCadParser.Domain;

/// <summary>
/// /parse 接口输出契约（对应设计文档 §3.3）。
/// 坐标单位统一为毫米。
/// </summary>
public class CadParseResult
{
    public bool Success { get; set; }
    public string Units { get; set; } = "mm";
    public BoundsDto? DrawingBounds { get; set; }
    public PreviewDto? Preview { get; set; }
    public List<RoomDto> Rooms { get; set; } = new();
    public List<UnnamedRegionDto> UnnamedRegions { get; set; } = new();
    public List<QualityIssue> QualityIssues { get; set; } = new();
    public string? ErrorCode { get; set; }
    public string? ErrorMessage { get; set; }
}

public class BoundsDto
{
    public double MinX { get; set; }
    public double MinY { get; set; }
    public double MaxX { get; set; }
    public double MaxY { get; set; }
}

public class PointDto
{
    public double X { get; set; }
    public double Y { get; set; }
}

/// <summary>与房间多边形使用同一 CAD 坐标范围渲染的规范预览。</summary>
public class PreviewDto
{
    public string Format { get; set; } = "png";
    public int Width { get; set; }
    public int Height { get; set; }
    public BoundsDto? Bounds { get; set; }
    public string? PngBase64 { get; set; }
}

public class RoomDto
{
    /// <summary>主标签（房间名原文，如"主卧"）。P2 起由标签关联填充。</summary>
    public string? Label { get; set; }
    /// <summary>room_type 字典码（LIVING_ROOM/DINING_ROOM/BEDROOM/KITCHEN/BATHROOM/BALCONY/STUDY/HALLWAY/OTHER）。</summary>
    public string? RoomType { get; set; }
    /// <summary>同空间内的其他标签（如客厅所在大空间内的"餐厅""玄关"）。</summary>
    public List<string>? MemberLabels { get; set; }
    /// <summary>所属合并空间名（如"客餐厅、厨房及过道"，来自铺贴合并标注）。</summary>
    public string? SpaceGroup { get; set; }
    /// <summary>外环顶点，毫米坐标 [[x,y],...]。</summary>
    public double[][] Polygon { get; set; } = Array.Empty<double[]>();
    public BoundsDto? BBox { get; set; }
    public double WidthMm { get; set; }
    public double DepthMm { get; set; }
    public double AreaM2 { get; set; }
    public PointDto? LabelPoint { get; set; }
    public string DimensionSource { get; set; } = "cad_geometry";
    public DimensionCheckDto? DimensionCheck { get; set; }
    public string Confidence { get; set; } = "high";
}

public class DimensionCheckDto
{
    /// <summary>标注值："5200×4300"（DIMENSION）或"面积: 22.75㎡"（铺贴标注）。</summary>
    public string? Annotated { get; set; }
    public bool Consistent { get; set; }
}

public class UnnamedRegionDto
{
    public double[][] Polygon { get; set; } = Array.Empty<double[]>();
    public BoundsDto? BBox { get; set; }
    public double WidthMm { get; set; }
    public double DepthMm { get; set; }
    public double AreaM2 { get; set; }
    public PointDto? LabelPoint { get; set; }
    public string? Hint { get; set; }
}

public class QualityIssue
{
    public string Level { get; set; } = "warn";
    public string Code { get; set; } = "";
    public string Message { get; set; } = "";
}
