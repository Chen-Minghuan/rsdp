package com.rsdp.agent.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建会话请求（与前端 CreateAgentSessionRequest 契约对应）。
 */
@Data
public class CreateSessionRequest {

    /** 设计师代录时的客户名（可空）。 */
    @Size(max = 128, message = "客户名最长 128 字符")
    private String customerName;
}
