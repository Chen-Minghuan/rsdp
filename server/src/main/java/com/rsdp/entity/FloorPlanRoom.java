package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 户型图空间识别明细实体（V36，每个识别出的空间一行，人工校正就地更新）。
 */
@Data
@TableName("floor_plan_room")
public class FloorPlanRoom {

    @TableId
    private String roomId;
    private String analysisId;

    /** 空间类型，引用 room_type 字典码（LIVING_ROOM/BEDROOM/...）。 */
    private String roomType;

    /** 归一化坐标 {x, y, w, h} [0,1]。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String bbox;

    /** 开间 mm（人工校正后为准）。 */
    private Integer widthMm;

    /** 进深 mm。 */
    private Integer depthMm;

    private BigDecimal areaM2;

    /** 尺寸来源：ocr_text / scale_calc / ai_estimate / manual。 */
    private String dimensionSource;

    /** 尺寸置信度：high/mid/low。 */
    private String dimensionConfidence;

    /** 图上尺寸标注原文（如 "4200×3800"）。 */
    private String dimensionText;

    private Integer sortOrder;

    /** 空间标签原文（V11，CAD 通道落库；未命名空间为"未命名空间 N"）。 */
    private String label;

    /** 房间外环顶点，毫米坐标 [[x,y],...]（V11，CAD 通道）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String polygon;

    /** 质心 {"x":..,"y":..}（V11，CAD 通道）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String centroid;

    /** 几何来源（V11）：ai_vision（视觉识别）/ cad_geometry（CAD 解析）。 */
    private String geometrySource;

    /** 人工删除误识别空间（软删，@TableLogic 模式）。 */
    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
