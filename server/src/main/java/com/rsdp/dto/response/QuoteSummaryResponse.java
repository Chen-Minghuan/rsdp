package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价单汇总。
 */
@Data
public class QuoteSummaryResponse {

    private BigDecimal totalPrice;
    private int itemCount;
    private int factoryCount;
    private int maxLeadTimeDays;
}
