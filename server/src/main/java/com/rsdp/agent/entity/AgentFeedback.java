package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 反馈实体（agent_feedback，V10）。
 */
@Data
@TableName("agent_feedback")
public class AgentFeedback {

    @TableId
    private String feedbackId;

    private String sessionId;

    /** 指向 recommend_item。 */
    private String itemId;

    private String batchId;

    /** 动作：confirm/reject/modify。 */
    private String action;

    /** 解析后的反馈结构（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String parsed;

    private LocalDateTime createdAt;
}
