using RsdpCadParser.Domain;

namespace RsdpCadParser.SceneAssembly;

/// <summary>
/// ParsedCadScene（目标架构 §3.7 Stage ⑦ Scene Assembly 输出契约）：
/// .NET Parser 自动产出的不可变场景事实——可重复解析、可回归，允许 UNKNOWN / NEEDS_REVIEW 存在。
/// 与 Working Scene（Java 组合 Parsed + Semantic Evidence + Human Edit）和
/// Confirmed Scene（用户确认 + 原子激活）严格区分；本对象只由 Parser 产生，AI/人工不得回改。
/// 坐标单位统一为毫米；本对象叠加在 /parse 顶层响应 parsedScene 字段，旧字段语义不变。
/// </summary>
public class ParsedCadScene
{
    public SceneMetadata Metadata { get; set; } = new();
    public UnitResolutionDto? UnitResolution { get; set; }
    public TargetSceneDto? TargetScene { get; set; }
    public List<TargetSceneCandidateDto> TargetSceneCandidates { get; set; } = new();

    /// <summary>空间对象：命名房间（named=true）与未命名空间（named=false，带候选名 hint）统一入列。</summary>
    public List<SceneRoom> Rooms { get; set; } = new();

    /// <summary>墙体对象：实心墙身（SOLID：HATCH/WIPEOUT/SOLID 等价物）与墙图层线（LINE）。</summary>
    public List<SceneWall> Walls { get; set; } = new();

    /// <summary>门对象：Stage ④ 门窗对象化（P2-C）前恒为空数组。</summary>
    public List<object> Doors { get; set; } = new();

    /// <summary>窗对象：Stage ④ 门窗对象化（P2-C）前恒为空数组。</summary>
    public List<object> Windows { get; set; } = new();

    /// <summary>家具候选（与顶层 furniture 同一份事实，含 P1 清洗/去重/视觉分组标记）。</summary>
    public List<FurnitureDto> Furniture { get; set; } = new();

    /// <summary>Raw Dimension 只读事实（测量值/文字中点/图层/溯源）；role 分类属 P2-A，当前恒 UNKNOWN。</summary>
    public List<SceneDimension> Dimensions { get; set; } = new();

    /// <summary>尺寸链：P2-A DimensionChainBuilder 产出，当前恒为空数组。</summary>
    public List<object> DimensionChains { get; set; } = new();

    /// <summary>Scene Graph 关系边（当前仅 FURNITURE_IN_ROOM：锚点 PIP + 轮廓重叠复核产出）。</summary>
    public List<SceneRelation> Relations { get; set; } = new();

    public List<QualityIssue> QualityIssues { get; set; } = new();
    public Dictionary<string, string> Capabilities { get; set; } = new();
}

/// <summary>场景元数据：输入指纹 + Parser/契约版本 + 配置指纹，支撑可重复解析与回归对账。</summary>
public class SceneMetadata
{
    /// <summary>输入文件字节 sha256（小写 hex 全量）。</summary>
    public string InputHash { get; set; } = "";
    public string ParserVersion { get; set; } = "";
    public string SceneVersion { get; set; } = "";
    /// <summary>ParserOptions 序列化 sha256 前 8 位：同输入同配置 → 同场景，配置漂移可察觉。</summary>
    public string ConfigHash { get; set; } = "";
}

/// <summary>场景空间对象（RoomDto / UnnamedRegionDto 的统一装配视图）。</summary>
public class SceneRoom
{
    /// <summary>稳定空间 ID（ROOM-###，命名房间与未命名空间同一序列）。</summary>
    public string? RoomId { get; set; }
    /// <summary>true=命名房间；false=未命名空间（待人工命名，hint 给候选名）。</summary>
    public bool Named { get; set; }
    public string? Label { get; set; }
    public string? RoomType { get; set; }
    public List<string>? MemberLabels { get; set; }
    public string? SpaceGroup { get; set; }
    /// <summary>外环顶点，毫米坐标 [[x,y],...]。</summary>
    public double[][] Polygon { get; set; } = Array.Empty<double[]>();
    public BoundsDto? BBox { get; set; }
    public double WidthMm { get; set; }
    public double DepthMm { get; set; }
    public double AreaM2 { get; set; }
    public PointDto? LabelPoint { get; set; }
    public string? DimensionSource { get; set; }
    public DimensionCheckDto? DimensionCheck { get; set; }
    /// <summary>命名房间沿用 RoomDto.Confidence；未命名空间无置信度来源，置 null（不虚构）。</summary>
    public string? Confidence { get; set; }
    /// <summary>未命名空间的候选名提示（堆叠标签降级 / 最近标签）。</summary>
    public string? Hint { get; set; }
}

/// <summary>场景墙体对象（WallExtractor 产出的装配视图；尚未做 WallPair/多策略证据组合，属 P2-C）。</summary>
public class SceneWall
{
    /// <summary>"W-" + 几何内容哈希（同输入重复解析稳定）。</summary>
    public string WallId { get; set; } = "";
    /// <summary>SOLID（实心墙身多边形）| LINE（墙图层线/多段线）。</summary>
    public string Kind { get; set; } = "";
    /// <summary>来源策略：WALL_SOLID（HATCH/WIPEOUT/SOLID 实心墙身）| WALL_LAYER_LINE（墙图层线兜底）。</summary>
    public string Source { get; set; } = "";
    /// <summary>SOLID：外环闭合点列 [[x,y],...]。</summary>
    public double[][]? Polygon { get; set; }
    /// <summary>LINE：线顶点序列 [[x,y],...]。</summary>
    public double[][]? Points { get; set; }
    /// <summary>LINE 几何长度（毫米）。</summary>
    public double? LengthMm { get; set; }
    /// <summary>SOLID 实心面积（平方米）。</summary>
    public double? AreaM2 { get; set; }
    public BoundsDto? BBox { get; set; }
}

/// <summary>
/// Raw Dimension 只读事实（目标架构 §6 RawDimensionExtractor 的最小装配）：
/// 只搬运已有抽取字段，不判断"这是谁的尺寸"；role/association 属 P2-A，当前恒 UNKNOWN / 空。
/// </summary>
public class SceneDimension
{
    /// <summary>"DIM-" + 内容哈希（来源句柄优先，否则图层|测量值|位置）。</summary>
    public string DimensionId { get; set; } = "";
    /// <summary>测量值（毫米，归一化后）。</summary>
    public double? MeasurementMm { get; set; }
    /// <summary>标注文字中点（毫米）。</summary>
    public PointDto? TextPosition { get; set; }
    public string? Layer { get; set; }
    /// <summary>来源 CAD 实体句柄（块展开图元携带；模型空间直接实体为 null）。</summary>
    public string? SourceHandle { get; set; }
    /// <summary>INSERT 句柄路径（块内标注）；非块内为 null。</summary>
    public List<string>? InsertPath { get; set; }
    /// <summary>所属块定义名（块内标注）；非块内为 null。</summary>
    public string? BlockDefinitionName { get; set; }
    /// <summary>角色分类（P2-A DimensionRoleClassifier 待实现）：当前恒 UNKNOWN。</summary>
    public string Role { get; set; } = "UNKNOWN";
}

/// <summary>Scene Graph 关系边（Stage ⑥ Topology & Association 的最小装配）。</summary>
public class SceneRelation
{
    /// <summary>关系类型：FURNITURE_IN_ROOM（家具→空间归属）。</summary>
    public string Type { get; set; } = "";
    /// <summary>源对象 ID（如 furnitureId）。</summary>
    public string FromId { get; set; } = "";
    /// <summary>目标对象 ID（如 roomId）。</summary>
    public string ToId { get; set; } = "";
    /// <summary>判定来源：ANCHOR_PIP_OVERLAP（锚点 PIP + 轮廓重叠复核）。</summary>
    public string Source { get; set; } = "";
    /// <summary>置信度（沿用家具检测置信度）。</summary>
    public string Confidence { get; set; } = "low";
}
