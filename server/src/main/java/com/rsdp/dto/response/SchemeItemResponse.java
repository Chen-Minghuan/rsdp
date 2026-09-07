package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 搭配方案项响应。
 */
@Data
public class SchemeItemResponse {

    private Long schemeItemId;
    private String rspuId;
    private String rspuName;
    private String primaryImageUrl;

    private String rskuId;
    private String factoryCode;
    private String factoryName;
    private String factorySku;

    private BigDecimal factoryPrice;
    /** 参考售价（销售价口径：该 RSPU 候选 RSKU 的最低标准售价，全角色可见；未定价为 null）。 */
    private BigDecimal salePrice;
    private Integer quantity;
    private BigDecimal subtotal;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer sortOrder;
    /** 生效的空间字典码（scheme_item.space_tag 覆盖优先，空则回退产品 rspu_scene 首场景码；无空间为 null，前端归「未分区」） */
    private String spaceTag;
    /** 空间显示名（生效码的场景字典名；码已从字典删除时原样返回码；无空间为 null） */
    private String spaceTagName;
    /** 是否人工覆盖的空间标签（scheme_item.space_tag 非空；false=跟随产品推导） */
    private boolean spaceTagOverridden;
}
