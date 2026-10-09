package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * CAD 分析人工校正 Diff 反馈实体（V20，设计文档 V1.2.3 §28.2 Recognition Diff）。
 *
 * <p>只记录变化（ADD/DELETE/UPDATE/SPLIT/MERGE），不反向修改原始自动结果；
 * 作为算法改进与 Benchmark 沉淀材料。</p>
 */
@Data
@TableName("cad_analysis_feedback")
public class CadAnalysisFeedback {

    /** 对象类型。 */
    public static final String OBJECT_TYPE_ROOM = "ROOM";
    public static final String OBJECT_TYPE_FURNITURE = "FURNITURE";

    /** 反馈类型。 */
    public static final String TYPE_ADD = "ADD";
    public static final String TYPE_DELETE = "DELETE";
    public static final String TYPE_UPDATE = "UPDATE";
    public static final String TYPE_SPLIT = "SPLIT";
    public static final String TYPE_MERGE = "MERGE";

    @TableId
    private String feedbackId;
    private String analysisId;
    private Integer analysisRevision;
    private String parserVersion;

    /** ROOM / FURNITURE。 */
    private String objectType;

    private String objectId;
    private String fieldName;
    private String predictedValue;
    private String confirmedValue;

    /** ADD / DELETE / UPDATE / SPLIT / MERGE。 */
    private String feedbackType;

    /** 变化细节（如 SPLIT/MERGE 的源/目标 ID 列表，UPDATE 的字段前后值）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String detail;

    private String createdBy;
    private LocalDateTime createdAt;
}
