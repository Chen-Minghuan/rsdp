package com.rsdp.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 会话详情响应（与前端 AgentSessionDetail 契约逐字段对应）。
 */
@Data
public class AgentSessionDetailResponse {

    private AgentSessionResponse session;

    private List<AgentMessageResponse> messages;

    /** 当前需求档案（无任何版本时为 null）。 */
    private RequirementProfileResponse requirement;

    private List<ConfirmedItemResponse> confirmedItems;
}
