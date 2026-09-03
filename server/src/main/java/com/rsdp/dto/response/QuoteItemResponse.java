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

    private BigDecimal factoryPrice;
    private Integer quantity;
    private BigDecimal subtotal;
    /** 标准售价（仅 sale 口径返回；对客户可见，明文） */
    private BigDecimal salePrice;
    /** 售价是否低于成本（仅 sale 口径；清库存场景提示） */
    private boolean belowCost;
    /** 成本价（仅 sale 口径且有出厂价查看权限时返回，绝不向无权限角色泄露） */
    private BigDecimal costPrice;
    /** 毛利 = 售价 − 成本（仅 sale 口径且有出厂价查看权限时返回） */
    private BigDecimal marginAmount;
    private String priceBand;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
}
