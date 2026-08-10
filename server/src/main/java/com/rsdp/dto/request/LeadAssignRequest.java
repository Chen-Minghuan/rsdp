package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 留资分配请求（PUT /api/v1/leads/{leadId}/assign）。
 */
@Data
public class LeadAssignRequest {

    /** 跟进人用户名。 */
    @NotBlank(message = "跟进人不能为空")
    @Size(max = 64, message = "跟进人长度不能超过 64")
    private String assignee;
}
