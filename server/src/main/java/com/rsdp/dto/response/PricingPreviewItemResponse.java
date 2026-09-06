package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 定价试算清单行。
 *
 * <p>试算基准：产品在售（status=active）且成本最低的 RSKU（与产品列表"最低出厂价"口径一致）。
 * 成本与毛利率按现有 factory_price 权限掩码：无权限角色不返回。</p>
 */
@Data
public class PricingPreviewItemResponse {

    private String rspuId;
    private String rspuCode;
    private String productName;
    /** 产品主图 URL（image_assets 主图，可能为空） */
    private String primaryImageUrl;
    private String categoryCode;
    private String categoryName;
    /** 最低成本 RSKU 的成本价（仅有出厂价查看权限时返回） */
    private BigDecimal costPrice;
    /** 标准售价（未定价时为 null） */
    private BigDecimal salePrice;
    /** 售价来源：MANUAL / CATEGORY_RULE / GLOBAL / NONE */
    private String priceSource;
    /** 生效倍率（自动计价 CATEGORY_RULE/GLOBAL 时返回） */
    private BigDecimal appliedMultiplier;
    /** 毛利率 = (售价 − 成本) / 售价（四位小数；仅有出厂价查看权限时可算时返回） */
    private BigDecimal marginRate;
    /** 售价是否低于成本（清库存场景提示） */
    private boolean belowCost;
}
