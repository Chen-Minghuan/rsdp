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

    /** 绑定的已确认户型分析 ID（W2，可空=纯口头描述会话）。 */
    @Size(max = 64, message = "户型分析 ID 最长 64 字符")
    private String floorPlanAnalysisId;
}
