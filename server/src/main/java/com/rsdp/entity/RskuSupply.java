package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.EncryptTypeHandler;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * RSKU 供应单元实体。
 *
 * <p>v3.3 P0-D（Flyway V14）收口：Identity =
 * {@code (variant_id, factory_code, supplier_configuration_key)}（设计文档 §32.1），
 * {@code rspuId} 保留查询用途、不再参与 Identity；{@code supplierConfigurationKey}
 * 无数据库默认值，所有写路径必须显式赋值（§34）。</p>
 */
@Data
@TableName("rsku_supply")
public class RskuSupply {

    @TableId
    private String rskuId;
    private String rskuCode;
    private String rspuId;
    private String variantId;
    private String factoryCode;
    private String factorySku;

    @TableField(typeHandler = EncryptTypeHandler.class)
    private BigDecimal factoryPrice;

    private String priceBand;
    private String productLevel;
    private String materialCode;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String shippingWarehouseId;
    private String structureStrengthRating;
    private String flameRetardantCapability;
    private String factoryPhotoPath;
    private Integer factoryCreditScore;
    private BigDecimal onTimeRate;
    private BigDecimal qualityReturnRate;
    private String diffNotes;
    private String quoteConfidence;
    private LocalDate priceUpdated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;

    // ---------------- v3.3 P0-D（Flyway V14）----------------

    /** RSKU 状态机（§36）：active/inactive/expired/suspended/discontinued */
    private String status;

    /** 报价币种（Quote 基础口径之一） */
    private String currencyCode;
    /** 是否含税（Quote 基础口径之一） */
    private Boolean taxIncluded;
    /** 计价单位（Quote 基础口径之一） */
    private String priceUnit;
    /** 起订量单位 */
    private String moqUnit;
    /** 报价日期 */
    private LocalDate quoteDate;
    /** 生效日期 */
    private LocalDate effectiveFrom;
    /** 失效日期 */
    private LocalDate effectiveTo;

    /** 供应商原始等级码（§18.2，原文保存不归一） */
    private String supplierMaterialGradeCode;
    /** 供应商原始等级原文（§18.2，原文保存不归一） */
    private String supplierMaterialGradeText;
    /**
     * 供应配置身份（§18.2）：{@code CODE:<归一码> / TEXT:<归一原文> / BASE}；
     * RSKU Identity 三元组之一，写路径必须显式赋值（无数据库 DEFAULT）。
     */
    private String supplierConfigurationKey;
    /** 生成 supplierConfigurationKey 的归一规则集版本（§18.2/§18.3） */
    private String supplierConfigurationNormalizerVersion;

    /** 来源导入批次 */
    private String sourceBatchId;
    /** 来源行号 */
    private Long sourceRowId;

    /** 报价乐观并发版本（§37）：报价更新 quote_version+1，冲突即 quote_version_conflict */
    private Integer quoteVersion;

    /**
     * 字段级人工修正权威标记（§34.1）：JSON Pointer →
     * {@code {active, correctedBy, correctedAt, source}}；Excel/AI/重试不得覆盖 active 字段。
     */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String manualOverrideFields;
}
