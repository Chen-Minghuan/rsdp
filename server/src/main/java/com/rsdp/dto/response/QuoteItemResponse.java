package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价单项。
 */
@Data
public class QuoteItemResponse {

    private String rspuId;
    private String rspuName;
    /** 完整商品名称（来自 rspu_master.product_name，可能为空；空时前端/导出回退 rspuName 定位标签） */
    private String productName;
    private String primaryImageUrl;

    private String rskuId;
    private String factoryCode;
    private String factoryName;
    private String factorySku;

    /** 出厂价（成本）：仅 cost 口径且有出厂价查看权限时返回；sale（对客户）口径恒为 null */
    private BigDecimal factoryPrice;
    private Integer quantity;
    private BigDecimal subtotal;
    /** 标准售价（仅 sale 口径返回；对客户可见，明文） */
    private BigDecimal salePrice;
    /** 售价是否低于成本（仅 sale 口径；清库存场景提示） */
    private boolean belowCost;
    /** 空间字典码（仅方案语境报价附带：方案明细覆盖码优先，回退产品场景推导；独立构建器为 null） */
    private String spaceTag;
    /** 空间显示名（场景字典名；码已删时为码原文；无空间为 null） */
    private String spaceTagName;
    /** @deprecated sale 口径为对客户报价单，成本/毛利一律不返回（恒为 null）；保留字段仅为兼容 */
    private BigDecimal costPrice;
    /** @deprecated 同 costPrice，恒为 null */
    private BigDecimal marginAmount;
    private String priceBand;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
}
