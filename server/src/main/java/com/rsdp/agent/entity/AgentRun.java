package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 运行记录实体（agent_run，V10）。
 *
 * <p>checkpoint_id 为框架 checkpoint 标识，用于断点恢复。</p>
 */
@Data
@TableName("agent_run")
public class AgentRun {

    @TableId
    private String runId;

    private String sessionId;

    /** 状态：running/waiting_human/done/failed。 */
    private String status;

    /** 当前执行到的图节点。 */
    private String currentNode;

    /** 框架 checkpoint 标识（断点恢复）。 */
    private String checkpointId;

    /** Agent 框架版本。 */
    private String frameworkVersion;

    private String errorCode;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;
}
