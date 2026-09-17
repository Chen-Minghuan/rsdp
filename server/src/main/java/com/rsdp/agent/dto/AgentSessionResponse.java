package com.rsdp.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 会话响应（与前端 AgentSession 契约逐字段对应）。
 *
 * <p>不含任何内部字段（出厂价/成本等本就不在契约内），客户角色与平台员工返回同一结构。</p>
 */
@Data
public class AgentSessionResponse {

    private String sessionId;

    /** 设计师代录时的客户名（未代录为 null）。 */
    private String customerName;

    /** active/closed。 */
    private String status;

    private Integer currentVersionNo;

    private String summary;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
