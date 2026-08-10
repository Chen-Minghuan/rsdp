package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 留资跟进记录请求（POST /api/v1/leads/{leadId}/follow-logs）。
 */
@Data
public class LeadFollowLogRequest {

    /** 跟进内容（追加到 follow_log JSON 数组）。 */
    @NotBlank(message = "跟进内容不能为空")
    @Size(max = 500, message = "跟进内容长度不能超过 500")
    private String content;
}
