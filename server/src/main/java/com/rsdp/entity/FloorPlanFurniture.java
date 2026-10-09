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
 * 户型 Scene 家具对象实体（V20，设计文档 V1.2.3 §41）。
 *
 * <p>Revision scoped 对象表：逻辑键 (analysis_id, analysis_revision, furniture_id)。
 * 自动解析快照字段（sourceHandles/insertPath/blockName/evidence/geometry 与 annotated 尺寸等）
 * 落库后不可由人工编辑接口修改；人工可编辑字段为 category/semanticLabel/roomId/position/
 * rotationDeg/footprintPolygon/effective 尺寸/heightMm/sortOrder。</p>
 */
@Data
@TableName("floor_plan_furniture")
public class FloorPlanFurniture {

    /** 来源：INSERT（块参照）/ EXPLODED（炸开图元）/ MANUAL（人工新增）。 */
    public static final String SOURCE_KIND_INSERT = "INSERT";
    public static final String SOURCE_KIND_EXPLODED = "EXPLODED";
    public static final String SOURCE_KIND_MANUAL = "MANUAL";

    /** 确认状态。 */
    public static final String CONFIRM_AUTO = "AUTO";
    public static final String CONFIRM_CONFIRMED = "CONFIRMED";
    public static final String CONFIRM_MANUAL_ADDED = "MANUAL_ADDED";
    public static final String CONFIRM_MANUAL_EDITED = "MANUAL_EDITED";

    @TableId
    private String furnitureId;
    private String analysisId;
    private Integer analysisRevision;

    /** INSERT / EXPLODED / MANUAL。 */
    private String sourceKind;

    /** SOFA / BED / TABLE / CHAIR / CABINET / OTHER / UNKNOWN。 */
    private String category;

    private String semanticLabel;

    /** 归属房间（floor_plan_room.room_id，可空：跨房间/开放空间/目标区域外）。 */
    private String roomId;

    private String blockName;

    /** CAD 来源实体句柄数组（自动快照，不可变）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sourceHandles;

    /** INSERT 嵌套路径（自动快照，不可变）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String insertPath;

    /** 插入点 {"x":..,"y":..} 毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String position;

    private BigDecimal rotationDeg;

    /** 占地轮廓 [[x,y],...] 闭合多边形，毫米。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String footprintPolygon;

    private Integer geometryWidthMm;
    private Integer geometryDepthMm;
    private Integer annotatedWidthMm;
    private Integer annotatedDepthMm;

    /** 生效尺寸（确认值优先，其次标注值，最后几何值）。 */
    private Integer effectiveWidthMm;
    private Integer effectiveDepthMm;

    private Integer heightMm;
    private String dimensionSource;
    private String semanticSource;

    /** 来源证据（自动快照，不可变）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String evidence;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityFlags;

    private String detectionConfidence;
    private String categoryConfidence;

    /** AUTO / CONFIRMED / MANUAL_ADDED / MANUAL_EDITED。 */
    private String confirmationStatus;

    /** P1-A 候选清洗状态：VALID / REJECTED（尺寸或几何无效，仅标记不删除）。 */
    private String candidateStatus;

    /** P1 块定义几何指纹（局部坐标量化哈希）。 */
    private String geometryFingerprint;

    /** P1-B 去重组 ID。 */
    private String duplicateGroupId;

    /** P1-B 组内角色：PRIMARY / DUPLICATE。 */
    private String duplicateStatus;

    /** P1-B 组内实例总数。 */
    private Integer duplicateCount;

    /** P1-C 视觉语义分组 ID（同组共享一次视觉识别回填全部实例）。 */
    private String visionGroupId;

    /** P1-C 视觉语义识别置信度（0~1）。 */
    private BigDecimal visionConfidence;

    /** 跨 Revision 继承来源对象 ID（建议继承证据，§8.7）。 */
    private String matchedFromObjectId;

    /** 人工拆分/合并来源对象 ID 列表。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String derivedFromIds;

    private Integer sortOrder;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
