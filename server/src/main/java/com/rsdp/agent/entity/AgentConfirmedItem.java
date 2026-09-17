package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 确认条目实体（agent_confirmed_item，V10）。
 *
 * <p>idempotency_key 唯一，防重复确认。</p>
 */
@Data
@TableName("agent_confirmed_item")
public class AgentConfirmedItem {

    @TableId
    private String itemId;

    private String sessionId;

    /** 来源推荐条目。 */
    private String recommendItemId;

    private String rspuId;

    /** 确认规格（颜色/尺寸等，JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String spec;

    private Integer quantity;

    /** 状态：confirmed/cancelled。 */
    private String status;

    /** 幂等键，防重复确认。 */
    private String idempotencyKey;

    private String confirmedBy;

    private LocalDateTime createdAt;
}
