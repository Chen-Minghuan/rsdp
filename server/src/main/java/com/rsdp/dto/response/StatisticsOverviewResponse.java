package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 统计总览响应。
 */
@Data
public class StatisticsOverviewResponse {

    /** 方案总数 */
    private Long schemeCount;
    /** 方案总金额 */
    private BigDecimal totalAmount;
    /** 项目总数 */
    private Long projectCount;
    /** 平均方案金额 */
    private BigDecimal avgSchemeAmount;
    /** 本月新增方案数 */
    private Long monthNewSchemes;
}
