package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型 Scene 家具对象响应（V20，GET 详情 furniture[] 元素）。
 */
@Data
public class FloorPlanFurnitureResponse {

    private String furnitureId;

    /** INSERT / EXPLODED / MANUAL。 */
    private String sourceKind;

    /** SOFA / BED / TABLE / CHAIR / CABINET / OTHER / UNKNOWN。 */
    private String category;

    private String semanticLabel;
    private String roomId;
    private String blockName;

    private List<String> sourceHandles;
    private List<String> insertPath;

    /** 插入点 {"x":..,"y":..} 毫米。 */
    private FloorPlanPoint position;

    private BigDecimal rotationDeg;

    /** 占地轮廓 [[x,y],...] 闭合多边形，毫米。 */
    private List<List<Double>> footprintPolygon;

    private Integer geometryWidthMm;
    private Integer geometryDepthMm;
    private Integer annotatedWidthMm;
    private Integer annotatedDepthMm;
    private Integer effectiveWidthMm;
    private Integer effectiveDepthMm;
    private Integer heightMm;

    private String dimensionSource;
    private String semanticSource;

    private List<String> evidence;
    private List<String> qualityFlags;

    private String detectionConfidence;
    private String categoryConfidence;

    /** AUTO / CONFIRMED / MANUAL_ADDED / MANUAL_EDITED。 */
    private String confirmationStatus;

    /** P1-A 候选清洗状态：VALID / REJECTED。 */
    private String candidateStatus;

    /** P1-B 去重组 ID。 */
    private String duplicateGroupId;

    /** P1-B 组内角色：PRIMARY / DUPLICATE。 */
    private String duplicateStatus;

    /** P1-B 组内实例总数。 */
    private Integer duplicateCount;

    /** P1-C 视觉语义分组 ID。 */
    private String visionGroupId;

    /** P1-C 视觉语义识别置信度（0~1）。 */
    private java.math.BigDecimal visionConfidence;

    /** 跨 Revision 继承来源对象 ID。 */
    private String matchedFromObjectId;

    /** 人工拆分/合并来源对象 ID 列表。 */
    private List<String> derivedFromIds;

    private Integer sortOrder;
}
