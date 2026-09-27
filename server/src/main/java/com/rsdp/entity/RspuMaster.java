package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("rspu_master")
public class RspuMaster {
    @TableId
    private String rspuId;
    /**
     * 历史外部编码（历史兼容投影，设计文档 v3.3 §8）：只读展示为「历史外部编码」并支持
     * 受限兼容搜索；不再是平台主展示编码（主展示为 {@link #rspuCode}），禁止作为
     * supplier identity / global product identity / import idempotency identity。
     * 供应商源商品身份见 {@code supplier_product_ref}；Phase 5 起新导入不再写本字段
     * （全局唯一索引 uk_rspu_external_code 已由 V16 移除，本列仅保留历史值只读投影）。
     */
    private String externalCode;
    private String rspuCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    /** 产品名称（AI OCR 提取 / Excel 导入品名；纯图无文字时为空） */
    private String productName;
    /** 长文本描述原文（Excel 导入的材质解析/功能配置/配置说明等） */
    private String description;
    /** 零售参考价（销售价/含税价等，不加密） */
    private java.math.BigDecimal retailPrice;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sixDimTags;

    private String colorPrimaryName;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String colorPrimaryHsv;

    private String colorSecondary;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String materialTags;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String fabricTags;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String sceneTags;

    private String referencePriceBand;

    private String productLevel;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String budgetRange;

    private Integer warrantyYears;

    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String keySpecs;
    private String status;
    private String reviewStatus;
    private String reviewComment;
    private String aestheticsConfidence;
    private String sourceAgentVersion;
    /** 录入人（sys_user.user_id）；历史未知行留 NULL（V3） */
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    /**
     * 字段级人工修正权威标记（设计文档 v3.3 §34.1，Flyway V14）：
     * JSON Pointer → {@code {active, correctedBy, correctedAt, source}}；
     * Excel/AI/后台重试不得覆盖 active 字段，恢复自动管理是独立的授权人工操作。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String manualOverrideFields;
}
