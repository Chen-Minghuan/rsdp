package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 留资状态流转请求（PUT /api/v1/leads/{leadId}/status）。
 */
@Data
public class LeadStatusRequest {

    /** 目标状态：pending / contacted / done（仅允许向前流转）。 */
    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "pending|contacted|done", message = "状态仅允许 pending/contacted/done")
    private String status;
}
