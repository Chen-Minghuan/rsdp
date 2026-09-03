package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价单汇总。
 */
@Data
public class QuoteSummaryResponse {

    private BigDecimal totalPrice;
    /** 成本合计（仅 sale 口径且全部明细有出厂价查看权限时返回） */
    private BigDecimal totalCost;
    /** 毛利合计 = 售价总价 − 成本合计（仅 sale 口径且全部明细有出厂价查看权限时返回） */
    private BigDecimal totalMargin;
    private int itemCount;
    private int totalQuantity;
    private int factoryCount;
    private int maxLeadTimeDays;
}
