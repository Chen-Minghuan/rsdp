namespace RsdpCadParser.Parser;

/// <summary>
/// 解析流水线可调参数（可通过配置节 "Parser" 或环境变量 Parser__Xxx 覆盖）。
/// </summary>
public class ParserOptions
{
    /// <summary>子图聚类：实体包围盒间距超过该值则断开成簇（毫米）。</summary>
    public double ClusterGapMm { get; set; } = 10_000;

    /// <summary>端点吸附容差（毫米），用于线网法兜底多边形化。</summary>
    public double SnapToleranceMm { get; set; } = 5;

    /// <summary>候选房间面积下限（㎡）。金样本调参结果：1.5 可滤除门套/衣柜凹位残渣（凹位归并已吸收门洞楔）。</summary>
    public double MinRoomAreaM2 { get; set; } = 1.5;

    /// <summary>候选房间面积上限（㎡）。</summary>
    public double MaxRoomAreaM2 { get; set; } = 200;

    /// <summary>目标子图外包络外扩边距（毫米），用于构造减法外框。</summary>
    public double EnvelopeMarginMm { get; set; } = 500;

    /// <summary>判定空间分量贴着外框（室外）的距离容差（毫米）。</summary>
    public double ExteriorTouchToleranceMm { get; set; } = 1;

    /// <summary>墙图层映射正则（HATCH 缺失时的兜底线框来源）。金样本墙体在 0 层，故含 ^0$。</summary>
    public string WallLayerPattern { get; set; } = @"(?i)(^0$|墙|柱|wall|column|结构|承重)";

    /// <summary>墙体材质填充图案正则（ANSI31=砖/混凝土斜线，AR-CONC=混凝土，AR-B816=砖墙）。</summary>
    public string WallHatchPattern { get; set; } = @"(?i)(ANSI31|AR-CONC|AR-B816|AR-HBONE|AR-BRICK)";

    /// <summary>排除图层正则：这些图层上的填充绝不是墙体（吊顶/铺贴/家具/灯具等）。</summary>
    public string ExcludeWallLayerPattern { get; set; } = @"(?i)(天花|吊顶|铺贴|地面|家具|灯具|开关|ceiling|floor|furniture|light)";

    /// <summary>单个 HATCH 环面积下限（㎡），低于此值视为门垛/五金等小填充而非墙体。</summary>
    public double MinHatchRingAreaM2 { get; set; } = 0.05;

    /// <summary>目标子图区域 = 标签包围盒外扩边距（毫米）。</summary>
    public double TargetRegionMarginMm { get; set; } = 6_000;

    /// <summary>门图层正则（门扇/门洞线，用于封闭门洞的线网与凹位归并判定）。</summary>
    public string DoorLayerPattern { get; set; } = @"(?i)(^door|门)";

    /// <summary>门洞凹位归并：小于该面积（㎡）且贴门线、无文字的 cell 并入邻居。</summary>
    public double MaxNicheAreaM2 { get; set; } = 1.5;

    /// <summary>门槛石图层正则（铺贴/地面图层的 HATCH 实心是门槛石/挡水条）。</summary>
    public string SillLayerPattern { get; set; } = @"(?i)(铺贴|地面|门槛)";

    /// <summary>输出 drawingBounds = 房间多边形并集外包络 + 该边距（毫米），前端以此为图片叠加归一化基准。</summary>
    public double OutputBoundsMarginMm { get; set; } = 200;

    /// <summary>墙体并集形态学闭运算半径（毫米）：填补 < 2×该值 的门洞/入口缺口。0 = 不做闭运算。</summary>
    public double WallCloseGapMm { get; set; } = 0;

    /// <summary>
    /// 线网法门洞闭合上限（毫米）：仅连接近似共线、方向相反的墙线端点。
    /// 住宅门洞通常为 700~1500mm，1800mm 可兼容双开口，同时由方向约束避免跨房间斜连。
    /// 0 = 不补门洞。
    /// </summary>
    public double LineCloseGapMm { get; set; } = 1_800;

    /// <summary>房间名标签词典正则，用于目标子图选择。</summary>
    public string RoomLabelPattern { get; set; } =
        "客厅|主卧|次卧|卧室|小孩房|儿童房|餐厅|厨房|卫生间|主卫|公卫|客卫|次卫|阳台|书房|玄关|过道|走廊|衣帽间|储物间|门厅|茶室|客房|工人房|洗衣房|起居室";

    /// <summary>单个 HATCH 环面积超过该值（㎡）时不视为墙体实心（防地砖/地板整体填充误判）。0 = 不限制。</summary>
    public double MaxSingleHatchAreaM2 { get; set; } = 0;
}
