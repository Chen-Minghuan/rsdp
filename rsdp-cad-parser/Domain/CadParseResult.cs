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

    /// <summary>视觉语义 isolation 视图（P1-C，请求 withIsolation=true 时输出）：
    /// visionGroupId → 白底线稿 PNG base64（块局部坐标渲染，旋转已归一化，仅 VALID 候选）。</summary>
    public Dictionary<string, string>? IsolationImages { get; set; }
    public List<RoomDto> Rooms { get; set; } = new();
    public List<UnnamedRegionDto> UnnamedRegions { get; set; } = new();
    public List<QualityIssue> QualityIssues { get; set; } = new();
    public string? ErrorCode { get; set; }
    public string? ErrorMessage { get; set; }

    // ---- P0 新增契约字段（全部为可选输出，旧消费者可忽略）----

    /// <summary>Scene 契约版本（常量 "1.0"）。</summary>
    public string SceneVersion { get; set; } = "1.0";
    /// <summary>Parser 版本（常量 "1.2.3-p0"）。</summary>
    public string ParserVersion { get; set; } = "1.2.3-p0";
    /// <summary>SUCCESS | PARTIAL | NEEDS_REVIEW | FAILED。success=false 当且仅当 FAILED。</summary>
    public string? ParseStatus { get; set; }
    /// <summary>能力级状态：rooms/walls/doors/windows/furniture/dimensions ∈ SUCCESS|PARTIAL|NEEDS_REVIEW|NOT_IMPLEMENTED。</summary>
    public Dictionary<string, string>? Capabilities { get; set; }
    public UnitResolutionDto? UnitResolution { get; set; }
    public TargetSceneDto? TargetScene { get; set; }
    public List<TargetSceneCandidateDto>? TargetSceneCandidates { get; set; }
    public List<FurnitureDto>? Furniture { get; set; }
    /// <summary>不支持/降级的实体类型计数（替代静默跳过）。</summary>
    public Dictionary<string, int>? UnsupportedEntitySummary { get; set; }

    /// <summary>
    /// Stage ⑦ Scene Assembly 输出（目标架构 §3.7）：现有 Extractor 产出的统一装配视图。
    /// 纯新增字段，不改变顶层任何旧字段语义；旧消费者可忽略。
    /// </summary>
    public SceneAssembly.ParsedCadScene? ParsedScene { get; set; }
}

/// <summary>单位判定结果（设计文档 §7.1）。confirmed=false 时毫米值只是归一化数值，不是已确认物理尺寸。</summary>
public class UnitResolutionDto
{
    /// <summary>CAD 原始单位状态：MILLIMETER / CENTIMETER / METER / INCH / FOOT / UNITLESS / OTHER。</summary>
    public string RawUnit { get; set; } = "UNITLESS";
    /// <summary>文件头明确声明的单位；Unitless 时为 null。</summary>
    public string? DeclaredUnit { get; set; }
    public string CanonicalUnit { get; set; } = "mm";
    public double ScaleToMm { get; set; } = 1.0;
    /// <summary>HEADER | DIMENSION_INFERENCE | ASSUMED（P0 仅 HEADER/ASSUMED）。</summary>
    public string ScaleSource { get; set; } = "ASSUMED";
    public bool ScaleAssumed { get; set; } = true;
    public bool Confirmed { get; set; }
    public string Confidence { get; set; } = "low";
}

public class TargetSceneDto
{
    public string TargetId { get; set; } = "";
    public string SourceSpace { get; set; } = "MODEL_SPACE";
    public BoundsDto? Bounds { get; set; }
    public string SelectionSource { get; set; } = "AUTO";
    public string SelectionConfidence { get; set; } = "mid";
    public List<string> Reasons { get; set; } = new();
}

public class TargetSceneCandidateDto
{
    public string TargetId { get; set; } = "";
    public string SourceSpace { get; set; } = "MODEL_SPACE";
    public BoundsDto? Bounds { get; set; }
    public double Score { get; set; }
    public List<string> Reasons { get; set; } = new();
    public bool Recommended { get; set; }
}

/// <summary>INSERT 家具候选（设计文档 §18；P0 仅 sourceKind=INSERT）。</summary>
public class FurnitureDto
{
    /// <summary>"F-" + sha256(insertPath 句柄按 ":" 连接) 前 8 位 hex，同实例重复解析稳定。</summary>
    public string FurnitureId { get; set; } = "";
    public string SourceKind { get; set; } = "INSERT";
    public List<string> SourceHandles { get; set; } = new();
    public List<string> InsertPath { get; set; } = new();
    /// <summary>SOFA | BED | TABLE | CHAIR | CABINET | OTHER | UNKNOWN。</summary>
    public string Category { get; set; } = "UNKNOWN";
    public string? SemanticLabel { get; set; }
    public string? RoomId { get; set; }
    public string? BlockName { get; set; }
    /// <summary>占地轮廓内部锚点（毫米）。</summary>
    public PointDto? Position { get; set; }
    public double RotationDeg { get; set; }
    /// <summary>展开图元世界坐标 AABB 闭合矩形 [[x,y],...]（首尾相同）。</summary>
    public double[][]? FootprintPolygon { get; set; }
    public double? GeometryWidthMm { get; set; }
    public double? GeometryDepthMm { get; set; }
    public double? AnnotatedWidthMm { get; set; }
    public double? AnnotatedDepthMm { get; set; }
    public double? EffectiveWidthMm { get; set; }
    public double? EffectiveDepthMm { get; set; }
    public double? HeightMm { get; set; }
    public string? DimensionSource { get; set; }
    /// <summary>cad_block | cad_layer | geometry。</summary>
    public string? SemanticSource { get; set; }
    public List<string> Evidence { get; set; } = new();
    public List<string> QualityFlags { get; set; } = new();
    public string DetectionConfidence { get; set; } = "low";
    public string CategoryConfidence { get; set; } = "low";

    // ---- P1-A 候选清洗 / P1-B 去重分组 / P1-C 视觉语义分组 ----

    /// <summary>候选清洗状态（P1-A）：VALID | REJECTED（尺寸/几何无效，仅标记不删除，供复核恢复）。</summary>
    public string CandidateStatus { get; set; } = "VALID";

    /// <summary>块定义几何指纹（局部坐标量化哈希）：匿名块名无业务意义，指纹防同名误判。</summary>
    public string? GeometryFingerprint { get; set; }

    /// <summary>去重组 ID（DG-+哈希）：同块定义+同几何+同旋转+同位置（容差网格内）的多实例成组，≥2 才输出。</summary>
    public string? DuplicateGroupId { get; set; }

    /// <summary>PRIMARY（组代表实例）| DUPLICATE（重复副本：默认抑制展示但不删除，Scene 修正后可恢复）。</summary>
    public string? DuplicateStatus { get; set; }

    /// <summary>组内实例总数。</summary>
    public int? DuplicateCount { get; set; }

    /// <summary>组内全部实例的 furnitureId（含自身）。</summary>
    public List<string>? SourceInstances { get; set; }

    /// <summary>视觉语义分组 ID（VG-+哈希）：同块定义+同局部尺寸+同几何指纹，跨位置/旋转共享一次视觉识别并回填全部实例。</summary>
    public string? VisionGroupId { get; set; }
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
    /// <summary>稳定房间 ID（"ROOM-" + 按 labelPoint 排序的序号，同一解析结果内确定性生成）。</summary>
    public string? RoomId { get; set; }
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
    /// <summary>稳定空间 ID（ROOM-###，续命名房间序列）：未命名空间也参与家具归属（P1）。</summary>
    public string? RoomId { get; set; }
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
