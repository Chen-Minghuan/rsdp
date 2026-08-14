package com.rsdp.dto.response;

import com.rsdp.dto.FloorPlanBBox;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 户型图单个空间响应。
 */
@Data
public class FloorPlanRoomResponse {

    /** 空间 ID（FPR-<UUID>）。 */
    private String roomId;

    /** 空间类型字典码。 */
    private String roomType;

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
