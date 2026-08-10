package com.rsdp.dto.response;

import lombok.Data;

/**
 * 官网留资提交响应。
 */
@Data
public class LeadCreateResponse {

    /** 留资线索 ID。 */
    private String leadId;

    /** 初始状态（固定 pending）。 */
    private String status;
}
