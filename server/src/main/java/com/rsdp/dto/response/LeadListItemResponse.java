package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 管理端留资线索列表项（GET /api/v1/leads）。手机号脱敏展示。
 */
@Data
public class LeadListItemResponse {

    private String leadId;

    private String name;

    /** 脱敏手机号（如 138****6621）。 */
    private String phoneMasked;

    /** 来源：ai_match / site_form / design_booking。 */
    private String source;

    private String intent;

    private String budget;

    /** 跟进状态：pending / contacted / done。 */
    private String status;

    /** 跟进人用户名。 */
    private String assignee;

    /** 跟进记录条数。 */
    private Integer followLogCount;

    private LocalDateTime createdAt;
}
