package com.rsdp.agent.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 消息响应（与前端 AgentMessage 契约逐字段对应）。
 *
 * <p>messageType=cards 时 metadata.items 为推荐卡片数组；requirement 时 metadata 为档案结构。</p>
 */
@Data
public class AgentMessageResponse {

    private String messageId;

    /** user/assistant/system。 */
    private String role;

    /** text/cards/requirement/notice。 */
    private String messageType;

    private String content;

    /** 卡片/引用等附加数据（JSONB 原样透传为对象）。 */
    private Object metadata;

    private Long sequenceNo;

    private LocalDateTime createdAt;
}
