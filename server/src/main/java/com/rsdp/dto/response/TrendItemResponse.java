package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 月度趋势项。
 */
@Data
public class TrendItemResponse {

    /** 月份（YYYY-MM） */
    private String month;
    /** 当月新增方案数 */
    private Long schemeCount;
    /** 当月方案总金额 */
    private BigDecimal totalAmount;
}
