package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价单汇总。
 */
@Data
public class QuoteSummaryResponse {

    private BigDecimal totalPrice;
    /** @deprecated sale 口径为对客户报价单，成本合计一律不返回（恒为 null）；保留字段仅为兼容 */
    private BigDecimal totalCost;
    /** @deprecated 同 totalCost，恒为 null */
    private BigDecimal totalMargin;
    private int itemCount;
    private int totalQuantity;
    private int factoryCount;
    private int maxLeadTimeDays;
}
