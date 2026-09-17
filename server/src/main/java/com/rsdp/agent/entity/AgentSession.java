package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 会话实体（agent_session，V10）。
 *
 * <p>created_by 为 actor（操作者），customer_user_id 为 subject（需求归属客户，注册后回填）。
 * 并发控制：一会话最多一个 running run，由 active_run_id 记录。</p>
 */
@Data
@TableName("agent_session")
public class AgentSession {

    @TableId
    private String sessionId;

    /** actor：操作者（导购/代录人）。 */
    private String createdBy;

    /** subject：需求归属客户，注册后回填。 */
    private String customerUserId;

    /** 代录客户名。 */
    private String customerName;

    /** 状态：active/closed。 */
    private String status;

    /** 当前需求版本号。 */
    private Integer currentVersionNo;

    /** 会话摘要。 */
    private String summary;

    /** 并发控制：一会话最多一个 running run。 */
    private String activeRunId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
