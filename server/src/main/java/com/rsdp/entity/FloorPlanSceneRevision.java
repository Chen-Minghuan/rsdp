package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 户型 Scene Revision 头表实体（V20，设计文档 V1.2.3 §8.4.4）。
 *
 * <p>每个 Analysis Revision 一行：自动解析快照（{@code rawResult}，READY 后冻结、不含预览
 * pngBase64）、预览/质量问题引用、Room/Furniture 对象乐观版本（{@code roomRevision} /
 * {@code furnitureRevision}，与 analysisRevision 是两个维度，Invariant 4）、重复提交幂等键
 * （{@code roomSubmitBase/Hash}、{@code furnitureSubmitBase/Hash}）。</p>
 *
 * <p>联合主键 (analysis_id, analysis_revision)。<b>实体刻意不标 {@code @TableId}</b>：
 * MyBatis-Plus 单主键语义无法表达复合主键，updateById/saveOrUpdate 会生成缺
 * analysis_revision 条件的 UPDATE 命中同分析全部 Revision 行（曾致唯一约束爆炸）；
 * 查询/更新一律走 QueryWrapper 双条件或
 * {@link com.rsdp.mapper.FloorPlanSceneRevisionMapper#updateByRevision}。</p>
 */
@Data
@TableName("floor_plan_scene_revision")
public class FloorPlanSceneRevision {

    /** Revision 状态机。 */
    public static final String STATUS_PROCESSING = "PROCESSING";
    public static final String STATUS_READY = "READY";
    public static final String STATUS_EDITING = "EDITING";
    public static final String STATUS_READY_TO_ACTIVATE = "READY_TO_ACTIVATE";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SUPERSEDED = "SUPERSEDED";

    /** Revision 触发来源。 */
    public static final String TRIGGER_INITIAL_PARSE = "INITIAL_PARSE";
    public static final String TRIGGER_TARGET_SCENE_SWITCH = "TARGET_SCENE_SWITCH";
    public static final String TRIGGER_MANUAL_EDIT = "MANUAL_EDIT";
    public static final String TRIGGER_REPARSE = "REPARSE";
    public static final String TRIGGER_LEGACY_IMPORT = "LEGACY_IMPORT";

    private String analysisId;
    private Integer analysisRevision;
    private Integer parentAnalysisRevision;

    /** INITIAL_PARSE / TARGET_SCENE_SWITCH / MANUAL_EDIT / REPARSE / LEGACY_IMPORT。 */
    private String revisionTrigger;

    /** 目标 Scene ID（target-scene 切换时确定；视觉通道为空）。 */
    private String targetSceneId;

    /** 解析状态：SUCCESS / PARTIAL / NEEDS_REVIEW / FAILED（视觉通道为 null）。 */
    private String parseStatus;

    /** PROCESSING / READY / EDITING / READY_TO_ACTIVATE / ACTIVE / FAILED / SUPERSEDED。 */
    private String revisionStatus;

    /** Room 对象乐观版本（本 Revision 内独立序列，初始 1）。 */
    private Integer roomRevision;

    /** Furniture 对象乐观版本（本 Revision 内独立序列，初始 1）。 */
    private Integer furnitureRevision;

    /** 最近一次成功 rooms 提交的 baseRoomRevision（幂等重放判定）。 */
    private Integer roomSubmitBase;

    /** 最近一次成功 rooms 提交的请求内容哈希（幂等重放判定）。 */
    private String roomSubmitHash;

    private Integer furnitureSubmitBase;
    private String furnitureSubmitHash;

    private String parserVersion;
    private String parserConfigHash;
    private String sourceProfileVersion;
    private String sceneVersion;

    /** 单位解析结果（Parser unitResolution 契约快照）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String unitResolution;

    /** 本 Revision 不可变自动解析快照（不含预览 pngBase64，READY 后冻结）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rawResult;

    /** 本 Revision 规范预览图（指向 image_assets）。 */
    private String previewImageId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityIssues;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String targetSceneCandidates;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String drawingBounds;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String previewBounds;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime readyAt;
    private LocalDateTime confirmedAt;
    private LocalDateTime activatedAt;
    private String activatedBy;
}
