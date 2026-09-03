package com.rsdp.dto.response;

import lombok.Data;

/**
 * 定价试算总览计数。
 */
@Data
public class PricingPreviewSummaryResponse {

    /** 人工录入建议销售价（retail_price）的产品数。 */
    private long manual;
    /** 按品类倍率自动计价的产品数。 */
    private long categoryRule;
    /** 按全局倍率自动计价的产品数。 */
    private long global;
    /** 未定价（无建议销售价且无成本）的产品数。 */
    private long unpriced;
    /** 售价低于成本的产品数。 */
    private long belowCost;
    /** 在售产品总数。 */
    private long total;
}
