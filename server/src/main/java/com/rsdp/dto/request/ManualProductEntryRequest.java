package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 传统手工录入新产品请求。
 *
 * <p>平台运营人员在不调用 AI、不关联工厂报价的情况下，
 * 一次性录入一个新产品（RSPU + 默认变体 + 可选图片）。</p>
 */
@Data
public class ManualProductEntryRequest {

    // ==================== RSPU 基础信息 ====================

    @NotBlank(message = "品类码不能为空")
    private String categoryCode;

    @NotBlank(message = "风格定位不能为空")
    private String positioningLabel;

    /** 商品名称（可选），如「云屿三人位沙发」 */
    private String productName;

    private String colorPrimaryName;

    /** 材质标签字典码列表，例如 ["PE","LE"] */
    private List<String> materialTags;

    /** 面料标签字典码列表（软体类商品），例如 ["LI","KJ"] */
    private List<String> fabricTags;

    /** 场景标签字典码列表，例如 ["LIVING","OFFICE"] */
    private List<String> sceneTags;

    @NotBlank(message = "产品等级不能为空")
    private String productLevel;

    private Integer warrantyYears;

    // ==================== 默认变体信息 ====================

    @NotBlank(message = "变体显示名称不能为空")
    private String variantDisplayName;

    private String sizeCode;

    /** 具体尺寸 JSON，例如 {"w":560,"d":580,"h":780,"unit":"mm"} */
    private String dimensions;

    private String colorCode;

    @NotBlank(message = "变体主材质码不能为空")
    private String variantMaterialCode;

    /** 多种材质组合，例如 ["实木框架", "布艺座包"] */
    private List<String> materialMix;
}
