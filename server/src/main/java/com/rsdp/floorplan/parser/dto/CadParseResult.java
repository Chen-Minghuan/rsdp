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

    /** 识别出的房间列表。 */
    private List<Room> rooms = new ArrayList<>();

    /** 无标签空间列表（落库为 roomType=OTHER + 未命名空间 N）。 */
    private List<UnnamedRegion> unnamedRegions = new ArrayList<>();

    /** 质量门问题清单。 */
    private List<QualityIssue> qualityIssues = new ArrayList<>();

    private String errorCode;
    private String errorMessage;

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
}
