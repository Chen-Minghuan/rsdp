package com.rsdp.dto.response;

import com.rsdp.dto.FloorPlanBBox;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型图单个空间响应。
 */
@Data
public class FloorPlanRoomResponse {

    /** 空间 ID（FPR-<UUID>）。 */
    private String roomId;

    /** 空间类型字典码。 */
    private String roomType;

    /** 空间标签原文（CAD 通道；未命名空间为"未命名空间 N"），可空。 */
    private String label;

    /** 房间外环顶点，毫米坐标 [[x,y],...]（CAD 通道），可空。 */
    private List<List<Double>> polygon;

    /** 保证位于房间内部的标签锚点（CAD 毫米坐标），可空。 */
    private FloorPlanPoint labelPoint;

    /** 空间框（归一化坐标 [0,1]），可空。 */
    private FloorPlanBBox bbox;

    /** 开间 mm。 */
    private Integer widthMm;

    /** 进深 mm。 */
    private Integer depthMm;

    /** 面积（平方米，两位小数）。 */
    private BigDecimal areaM2;

    /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual。 */
    private String dimensionSource;

    /** 尺寸置信度：high/mid/low。 */
    private String dimensionConfidence;

    /** 图上尺寸标注原文。 */
    private String dimensionText;

    private Integer sortOrder;
}
