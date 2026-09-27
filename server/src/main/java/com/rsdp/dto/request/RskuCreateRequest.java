package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * RSKU 报价创建请求。
 */
@Data
public class RskuCreateRequest {

    private String rspuId;

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

    @NotBlank(message = "变体 ID 不能为空")
    private String variantId;
    private String factorySku;

    /**
     * 出厂价（Jackson 按 JSON 十进制文本精确反序列化为 BigDecimal，入库前 AES 加密）。
     *
     * <p>精度口径：前端以 JS number 提交，JSON.stringify 输出的是可精确往返的十进制文本，
     * 中间无浮点运算，因此 ≤ 999,999,999.99（两位小数以内）的金额全链路精确；
     * 超过该口径或三位以上小数的金额需改字符串传输后再评估。</p>
     */
    @NotNull(message = "出厂价不能为空")
    @Positive(message = "出厂价必须大于 0")
    private BigDecimal factoryPrice;

    private String materialCode;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String shippingWarehouseId;
    private String diffNotes;
    private String quoteConfidence;

    /** 产品等级，为空时按 变体 > RSPU 继承。 */
    private String productLevel;

    /** 工厂无对应能力等级时，是否自动扩展工厂能力。 */
    private Boolean autoExtendCapability;

    // ---------------- v3.3 P0-D：Supplier Configuration / Quote 口径（§18.2/§34） ----------------

    /** 供应商原始等级/配置码（可空；与 gradeText 推导 supplierConfigurationKey）。 */
    private String supplierMaterialGradeCode;

    /** 供应商原始等级/配置原文（可空）。 */
    private String supplierMaterialGradeText;

    /**
     * 显式供应配置 Key（可选）：Preview Snapshot 固化路径（quoteKey 内嵌）直接携带；
     * 为空时由 supplierMaterialGradeCode/Text 按 §18.2 优先级推导，均无则 BASE。
     * 非空时必须是合法形态（BASE 或 CODE:/TEXT: 前缀）。
     */
    private String supplierConfigurationKey;

    /** 报价币种（Quote 基础口径，默认 CNY）。 */
    private String currencyCode;

    /** 是否含税（Quote 基础口径）。 */
    private Boolean taxIncluded;

    /** 计价单位（Quote 基础口径）。 */
    private String priceUnit;

    /** 起订量单位。 */
    private String moqUnit;

    /** 报价日期。 */
    private java.time.LocalDate quoteDate;

    /** 生效日期。 */
    private java.time.LocalDate effectiveFrom;

    /** 失效日期。 */
    private java.time.LocalDate effectiveTo;

    // ---------------- v3.3 P0-F：Attempt 治理下的 Quote Policy（§47.1.5） ----------------

    /**
     * Quote Policy（可选；仅 Import Attempt 治理链路携带，值为 §47.1.5 枚举）。
     * null = 旧兼容语义（无条件覆盖更新）；非 null 时按 quotePolicy 日期/同值条件表决策。
     */
    private String quotePolicy;

    /** force_replace 时的覆盖理由（§47.1.5：必须非空并记审计）。 */
    private String quoteOverrideReason;

    /**
     * Quote Policy 决策说明收集器（可选）：STALE_QUOTE_IGNORED / keep_existing skipped 等
     * 非失败跳过说明追加进该列表，由导入行循环汇总进行级问题（对用户可见）。
     */
    private java.util.List<String> quotePolicyNotes;
}
