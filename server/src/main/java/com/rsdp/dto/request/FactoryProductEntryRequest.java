package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工厂单条录入请求。
 *
 * <p>工厂管理员在不调用 AI 的情况下，一次性录入一个新产品及其第一条 RSKU 报价。</p>
 */
@Data
public class FactoryProductEntryRequest {

    // ==================== RSPU 基础信息 ====================

    @NotBlank(message = "品类码不能为空")
    private String categoryCode;

    @NotBlank(message = "风格定位不能为空")
    private String positioningLabel;

    private String colorPrimaryName;

    /**
     * 材质标签 JSON 数组，例如 ["PE","LE"]
     */
    private List<String> materialTags;

    /**
     * 场景标签 JSON 数组，例如 ["LIVING","OFFICE"]
     */
    private List<String> sceneTags;

    /**
     * 六维标签 JSON 对象/数组。
     */
    private Object sixDimTags;

    @NotBlank(message = "产品等级不能为空")
    private String productLevel;

    private Integer warrantyYears;

    /**
     * 关键规格 JSON。
     */
    private Object keySpecs;

    // ==================== 默认变体信息 ====================

    @NotBlank(message = "变体显示名称不能为空")
    private String variantDisplayName;

    private String sizeCode;

    /**
     * 具体尺寸 JSON，例如 {"w":560,"d":580,"h":780,"unit":"mm"}
     */
    private String dimensions;

    private String colorCode;

    @NotBlank(message = "变体主材质码不能为空")
    private String variantMaterialCode;

    /**
     * 多种材质组合，例如 ["实木框架", "布艺座包"]
     */
    private List<String> materialMix;

    // ==================== 第一条 RSKU ====================

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

    private String factorySku;

    @NotNull(message = "出厂价不能为空")
    private BigDecimal factoryPrice;

    private String materialDescription;

    private Integer leadTimeDays;

    private Integer moq;

    private Integer warrantyYearsRsku;

    private String shippingFrom;

    private String diffNotes;

    private String quoteConfidence;

    /** 工厂无对应能力等级时，是否自动扩展工厂能力。 */
    private Boolean autoExtendCapability;
}
