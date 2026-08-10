package com.rsdp.dto.response;

import lombok.Data;

/**
 * 留资线索来源分布统计（GET /api/v1/leads/source-stats）。
 */
@Data
public class LeadSourceStatsResponse {

    /** AI 户型搭配来源线索数。 */
    private Long aiMatch;

    /** 官网表单来源线索数。 */
    private Long siteForm;

    /** 设计服务预约来源线索数。 */
    private Long designBooking;

    /** 待跟进线索数（导航角标同用）。 */
    private Long pending;
}
