package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 管理端工作台统计带（GET /api/v1/dashboard/summary）。
 */
@Data
public class DashboardSummaryResponse {

    /** 产品总数（RSPU，不含已删除）。 */
    private Long rspuTotal;

    /** 工厂报价总数（RSKU，不含已删除）。 */
    private Long rskuTotal;

    /** AI 识别通过率（百分比，如 96.2；无识别记录时为 null）。 */
    private BigDecimal aiPassRate;

    /** 本月订单额（到手价合计，不含已取消订单）。 */
    private BigDecimal monthOrderAmount;

    /** 今日新增留资线索数。 */
    private Long todayLeadCount;
}
