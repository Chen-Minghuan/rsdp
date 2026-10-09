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

    // ---- P0 资源守卫（设计文档 §24.3）----

    /// <summary>BLOCK 嵌套展开最大深度（顶层 INSERT 深度为 1）。超限分支停止展开并输出 BLOCK_EXPANSION_LIMIT。</summary>
    public int MaxBlockDepth { get; set; } = 8;

    /// <summary>块展开产出图元总数上限。超限停止后续块展开并输出 ENTITY_LIMIT。</summary>
    public int MaxExpandedEntities { get; set; } = 200_000;

    /// <summary>扁平图元总数上限（模型空间 + 块展开）。超限输出 ENTITY_LIMIT 并降级 PARTIAL。</summary>
    public int MaxTotalPrimitives { get; set; } = 500_000;

    /// <summary>/parse 单次解析最长时间（秒）。超时输出 PARSER_TIMEOUT（有部分结果时降级 PARTIAL）。</summary>
    public int MaxParseTimeSeconds { get; set; } = 60;

    /// <summary>平面性容差（毫米）：模型空间 Z 范围超过该值输出 NON_PLANAR_GEOMETRY。</summary>
    public double PlanarityToleranceMm { get; set; } = 10;

    // ---- P1 Scene-aware Block Fallback（块角色分类 + 建筑几何回退 + 堆叠标签降级）----

    /// <summary>
    /// 家具绝对尺寸上限（毫米）：块展开包围盒任一边超过该值判为方案级容器（SCENE_CONTAINER），
    /// 不作为单件家具输出（整图方案块常以匿名块封装整户几何，包围盒达数十米）。
    /// </summary>
    public double MaxFurnitureSpanMm { get; set; } = 8_000;

    /// <summary>方案级容器判定的展开图元数下限（需同时满足跨度超 MaxFurnitureSpanMm）。</summary>
    public int SceneContainerMinPrimitives { get; set; } = 100;

    /// <summary>标注角色（ANNOTATION）判定：块内文字/尺寸图元占比下限。</summary>
    public double AnnotationTextRatio { get; set; } = 0.8;

    /// <summary>
    /// 建筑几何回退的排除图层正则：回退线网多边形化时，这些图层的线/填充不参与空间围合
    /// （家具/洁具/家电/灯具/机电点位/窗帘/淡显/文字标注层等）。比 ExcludeWallLayerPattern 更宽，
    /// 因为深化图里这些图层大量存在于块内。
    /// </summary>
    public string FallbackExcludeLayerPattern { get; set; } =
        @"(?i)(家具|陈设|洁具|家电|灯具|窗帘|淡显|文字|标注|铺贴|地面|天花|吊顶|机电|烟感|喷淋|摄像|喇叭|探测器|幕帘|温感|无线|逃生|煤气|信号放大|defpoints|furn|light|equip|ceiling|floor|^k)";

    /// <summary>堆叠标签判定半径（毫米）：该半径内不同房间标签数达下限即判标签堆叠。</summary>
    public double StackedLabelRadiusMm { get; set; } = 500;

    /// <summary>堆叠标签判定：半径内不同标签数下限。</summary>
    public int StackedLabelMinCount { get; set; } = 3;

    /// <summary>建筑几何回退开关：直接实体主路径无空间产出时，用块展开建筑几何重试空间分析。</summary>
    public bool BlockGeometryFallbackEnabled { get; set; } = true;

    // ---- P1-A/B/C 家具候选清洗 / 去重 / 视觉语义分组 ----

    /// <summary>家具候选尺寸下限（毫米）：包围盒最大边低于该值判 REJECTED（FURNITURE_SIZE_TOO_SMALL），仅标记不删除。</summary>
    public double MinFurnitureDimMm { get; set; } = 100;

    /// <summary>去重位置量化网格（毫米）：同块同几何同旋转且位置落在同一网格的实例判为重复副本（多方案叠图）。</summary>
    public double DuplicatePositionToleranceMm { get; set; } = 50;

    /// <summary>是否在 /parse 响应中输出家具 isolation 视图（visionGroupId → PNG base64）。由请求方按需开启。</summary>
    public bool EmitIsolationImages { get; set; } = false;

    /// <summary>isolation 视图最大边长（像素）。</summary>
    public int IsolationImageMaxSide { get; set; } = 256;

    // ---- P1-E 栅格空间重建（深化图断线墙体的最终回退）----

    /// <summary>栅格重建开关：矢量路径（直接实体/块展开回退）均无产出时，墙线位图形态学重建。</summary>
    public bool RasterRebuildEnabled { get; set; } = true;

    /// <summary>栅格分辨率（毫米/像素）。</summary>
    public double RasterMmPerPx { get; set; } = 50;

    /// <summary>栅格画布最大边长（像素），超大图纸自动降分辨率。</summary>
    public int RasterMaxPx { get; set; } = 2048;

    /// <summary>墙体膨胀半径（毫米）：封闭 ≤2×该值的门/窗洞断口（默认 450 → 封 900mm 门洞）。</summary>
    public double RasterWallDilateMm { get; set; } = 450;

    /// <summary>栅格重建区域外扩边距（毫米）：保证房间不贴画布边（贴边分量会被丢弃）。</summary>
    public double RasterRegionMarginMm { get; set; } = 1_000;

    /// <summary>栅格重建填充率下限（面积/包围盒面积）：低于此值判为环形/细条残渣丢弃。</summary>
    public double RasterMinFillRatio { get; set; } = 0.15;

    /// <summary>
    /// 栅格重建的噪声图层排除正则（比 FallbackExcludeLayerPattern 宽）：深化图里 0 层/淡显层
    /// 常承载既有建筑结构（墙线），只剔除硬噪声（机电点位/文字/标注/窗帘等）。
    /// </summary>
    public string RasterExcludeLayerPattern { get; set; } =
        @"(?i)(文字|标注|dim|窗帘|机电|烟感|喷淋|摄像|喇叭|探测器|幕帘|温感|无线|逃生|煤气|信号放大|defpoints|洁具|家电|灯具|开关)";

    // ---- P2-S 房间轮廓贴合墙体内侧面（Wall Inner Face Snap）----

    /// <summary>墙体内侧面吸附总开关：三条空间链路（线网/块回退/栅格）产出的房间统一过 RoomWallSnapper。</summary>
    public bool WallSnapEnabled { get; set; } = true;

    /// <summary>
    /// 墙体内侧完成面图层正则（最高优先级 Inner Face 来源）：深化图的装饰完成面线
    /// 即现成的墙体内侧边界（如 601 的「W装饰完成面」图层，含卫生间闭合完成面环）。
    /// </summary>
    public string InnerFaceLayerPattern { get; set; } = @"(?i)(完成面|饰面|面层|inner.?face|finish.?line)";

    /// <summary>
    /// 房间边吸附到墙体内侧面的最大垂直距离（毫米）。参考封口半径取 150~300：
    /// 需覆盖双线墙厚度（~240mm）内房间边落在墙中线/外侧线的偏差，过大会误吸相邻平行墙。
    /// </summary>
    public double SnapMaxDistMm { get; set; } = 250;

    /// <summary>房间边与内侧面线段的方向夹角上限（度），超过判为不同墙不吸附。</summary>
    public double SnapMaxAngleDeg { get; set; } = 10;

    /// <summary>
    /// 吸附后面积变化容忍比例：超过该值判定吸附把房间吸崩（误吸到相邻空间墙面），
    /// 回退原轮廓并记 WALL_SNAP_REVERTED 质量门。
    /// </summary>
    public double SnapMaxAreaChangeRatio { get; set; } = 0.15;

    /// <summary>
    /// 门洞拉直链长上限（毫米）：两侧均已吸附的未命中边链（横跨门洞的虚拟封口的折线）
    /// 总长按不超过该值时拉直为一条边，消除门洞附近不规则折线。需 ≥ LineCloseGapMm。
    /// </summary>
    public double DoorStraightenMaxLenMm { get; set; } = 2_000;

    /// <summary>双线墙配对厚度窗口下限（毫米）：平行且间距落在窗口内的墙线配成一堵墙的两侧边线。</summary>
    public double WallPairMinThickMm { get; set; } = 60;

    /// <summary>双线墙配对厚度窗口上限（毫米）。</summary>
    public double WallPairMaxThickMm { get; set; } = 500;

    /// <summary>浅拷贝（所有字段为值类型/不可变字符串引用，安全）。</summary>
    public ParserOptions Clone() => (ParserOptions)MemberwiseClone();

    /// <summary>
    /// 块展开回退用配置副本：深化图墙体常在 0/自定义图层（匿名块封装），墙线判定从
    /// "墙图层命名匹配"反转为"非排除图层皆可围合"（排除项 = FallbackExcludeLayerPattern）。
    /// </summary>
    public ParserOptions CloneForBlockFallback()
    {
        var c = Clone();
        c.WallLayerPattern = @"(?i)^.+$";
        c.ExcludeWallLayerPattern = c.FallbackExcludeLayerPattern;
        return c;
    }
}
