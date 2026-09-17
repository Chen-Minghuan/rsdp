package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 消息实体（agent_message，V10）。
 *
 * <p>client_message_id 为客户端幂等 ID，非空时与 session_id 联合唯一；
 * sequence_no 为会话内单调递增序号。</p>
 */
@Data
@TableName("agent_message")
public class AgentMessage {

    @TableId
    private String messageId;

    private String sessionId;

    /** 所属运行（用户消息可为空）。 */
    private String runId;

    /** 客户端幂等 ID。 */
    private String clientMessageId;

    /** 角色：user/assistant/system。 */
    private String role;

    /** 消息类型：text/cards/requirement/notice。 */
    private String messageType;

    private String content;

    /** 卡片/引用等附加数据（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String metadata;

    /** 会话内单调递增序号。 */
    private Long sequenceNo;

    private LocalDateTime createdAt;
}
