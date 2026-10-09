package com.rsdp.floorplan.parser.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * rsdp-cad-parser 服务 POST /parse 输出契约（对应 rsdp-cad-parser/Domain/CadParseResult.cs，
 * CAD 户型导入 P3）。坐标单位统一为毫米；字段名为 camelCase JSON 契约，不可改。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CadParseResult {

    /** 解析是否成功；false 时 errorCode/errorMessage 给出原因（HTTP 422）。 */
    private boolean success;

    /** 坐标单位，恒为 mm。 */
    private String units;

    /** 图纸外包络（毫米），bbox 归一化的参照。 */
    private Bounds drawingBounds;

    /** 与房间多边形使用同一 CAD 坐标范围渲染的规范预览。 */
    private Preview preview;

    /** P1-C isolation 视图（visionGroupId → 白底线稿 PNG base64），请求 withIsolation=true 时返回。 */
    private java.util.Map<String, String> isolationImages;

/** 识别出的房间列表。 */
    private List<Room> rooms = new ArrayList<>();

    /** 无标签空间列表（落库为 roomType=OTHER + 未命名空间 N）。 */
    private List<UnnamedRegion> unnamedRegions = new ArrayList<>();

    /** 质量门问题清单。 */
    private List<QualityIssue> qualityIssues = new ArrayList<>();

    private String errorCode;
    private String errorMessage;

    // ===== CAD Scene Parser 升级（V1.2.3 §18.6 兼容契约，新增可选字段必须显式接收）=====

    /** Scene JSON 契约版本（如 "1.0"）。 */
    private String sceneVersion;

    /** Parser 发布版本。 */
    private String parserVersion;

    /** 解析状态：SUCCESS / PARTIAL / NEEDS_REVIEW / FAILED；success=false ⟺ FAILED。 */
    private String parseStatus;

    /** 能力声明（墙/门/窗/家具/尺寸是否输出）。 */
    private Capabilities capabilities;

    /** 单位解析结果。 */
    private UnitResolution unitResolution;

    /** 自动选定的目标 Scene。 */
    private TargetScene targetScene;

    /** 目标 Scene 候选列表（前端可读取后切换）。 */
    private List<TargetSceneCandidate> targetSceneCandidates = new ArrayList<>();

    /** 家具候选列表（P0-B 以 INSERT 家具为主）。 */
    private List<Furniture> furniture = new ArrayList<>();

    /** 未支持实体汇总（可读质量问题而非伪造完整清单）。 */
    private Object unsupportedEntitySummary;

    /** 范围框（毫米）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Bounds {
        private Double minX;
        private Double minY;
        private Double maxX;
        private Double maxY;
    }

    /** CAD 点坐标（毫米）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Point {
        private Double x;
        private Double y;
    }

    /** 规范预览元数据；pngBase64 在解析器适配层解码后会清空。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Preview {
        private String format;
        private Integer width;
        private Integer height;
        private Bounds bounds;
        private String pngBase64;
    }

    /** 识别出的房间。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Room {

        /** Parser 侧房间稳定 ID（同一 analysisRevision 内稳定；缺失时后端自行稳定生成）。 */
        private String roomId;

        /** 主标签（房间名原文，如"主卧"）。 */
        private String label;

        /** room_type 字典码（LIVING_ROOM/BEDROOM/KITCHEN/.../OTHER）。 */
        private String roomType;

        /** 外环顶点，毫米坐标 [[x,y],...]。 */
        private List<List<Double>> polygon = new ArrayList<>();

        private Bounds bBox;

        private Double widthMm;
        private Double depthMm;
        private Double areaM2;
        private Point labelPoint;

        /** 尺寸来源，恒为 cad_geometry。 */
        private String dimensionSource;

        /** DIMENSION 标注交叉验证结果。 */
        private DimensionCheck dimensionCheck;

        /** 置信度：high/mid/low。 */
        private String confidence;
    }

    /** DIMENSION 标注交叉验证。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DimensionCheck {

        /** 标注值原文："5200×4300" 或 "面积: 22.75㎡"。 */
        private String annotated;

        private Boolean consistent;
    }

    /** 无标签空间。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnnamedRegion {

        /** 稳定空间 ID（ROOM-###，续命名房间序列）：P1 起未命名空间也参与家具归属映射，可空（旧解析结果）。 */
        private String roomId;

        /** 外环顶点，毫米坐标 [[x,y],...]。 */
        private List<List<Double>> polygon = new ArrayList<>();

        private Bounds bBox;
        private Double widthMm;
        private Double depthMm;

        private Double areaM2;
        private Point labelPoint;

        /** 相邻标签提示。 */
        private String hint;
    }

    /** 质量门问题项。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QualityIssue {

        /** 级别：warn / error。 */
        private String level;

        private String code;
        private String message;
    }

    /** 能力声明（P0 未实现的能力不得阻断激活，按 capability contract 区分；值为状态字符串）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Capabilities {
        /** SUCCESS / PARTIAL / NOT_IMPLEMENTED 等。 */
        private String rooms;
        private String walls;
        private String doors;
        private String windows;
        private String furniture;
        private String dimensions;
    }

    /** 单位解析结果（§7.1 UnitResolver）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class UnitResolution {
        private String rawUnit;
        private String declaredUnit;
        private String canonicalUnit;
        private Double scaleToMm;
        private String scaleSource;
        private Boolean scaleAssumed;
        private Boolean confirmed;
        private String confidence;
    }

    /** 目标 Scene（§8）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetScene {
        private String targetId;
        private String sourceSpace;
        private Bounds bounds;
        private String selectionSource;
        private String selectionConfidence;
        private List<String> reasons = new ArrayList<>();
    }

    /** 目标 Scene 候选（§8.1 读取契约）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TargetSceneCandidate {
        private String targetId;
        private String sourceSpace;
        private Bounds bounds;

        /** 候选预览元数据（pngBase64 在解析器适配层剥离，不落 raw_result）。 */
        private Preview preview;

        private Double score;
        private List<String> reasons = new ArrayList<>();
        private Boolean recommended;
    }

    /** 家具候选（P0-B，INSERT 家具为主；§10/§11/§18.6）。 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Furniture {

        /** 对象 ID（同一 analysisRevision 内稳定，非空）。 */
        private String furnitureId;

        /** 来源：INSERT / EXPLODED。 */
        private String sourceKind;

        private List<String> sourceHandles = new ArrayList<>();
        private List<String> insertPath = new ArrayList<>();

        /** SOFA / BED / TABLE / CHAIR / CABINET / OTHER / UNKNOWN。 */
        private String category;

        private String semanticLabel;

        /** 归属房间（Parser 侧 roomId 或后端 room_id，可空）。 */
        private String roomId;

        private String blockName;

        private Point position;
        private Double rotationDeg;

        /** 占地轮廓 [[x,y],...] 闭合多边形，毫米。 */
        private List<List<Double>> footprintPolygon = new ArrayList<>();

        private Double geometryWidthMm;
        private Double geometryDepthMm;
        private Double annotatedWidthMm;
        private Double annotatedDepthMm;
        private Double effectiveWidthMm;
        private Double effectiveDepthMm;
        private Double heightMm;

        private String dimensionSource;
        private String semanticSource;

        private List<String> evidence = new ArrayList<>();
        private List<String> qualityFlags = new ArrayList<>();

        private String detectionConfidence;
        private String categoryConfidence;

        /** P1-A 候选清洗状态：VALID / REJECTED（仅标记不删除）。 */
        private String candidateStatus;

        /** P1 块定义几何指纹（局部坐标量化哈希）。 */
        private String geometryFingerprint;

        /** P1-B 去重组 ID（同块+同几何+同旋转+同位置网格），可空。 */
        private String duplicateGroupId;

        /** P1-B 组内角色：PRIMARY / DUPLICATE。 */
        private String duplicateStatus;

        /** P1-B 组内实例总数。 */
        private Integer duplicateCount;

        /** P1-B 组内全部实例的 Parser furnitureId。 */
        private List<String> sourceInstances;

        /** P1-C 视觉语义分组 ID（同组共享一次视觉识别）。 */
        private String visionGroupId;
    }
}
