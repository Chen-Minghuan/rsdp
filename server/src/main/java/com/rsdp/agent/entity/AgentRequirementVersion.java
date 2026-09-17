package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 需求版本实体（agent_requirement_version，V10）。
 *
 * <p>constraints 为本版需求约束全量快照，patch 记录本版应用的 operations 留痕，
 * (session_id, version_no) 唯一。</p>
 */
@Data
@TableName("agent_requirement_version")
public class AgentRequirementVersion {

    @TableId
    private String versionId;

    private String sessionId;

    private Integer versionNo;

    /** 本版需求约束全量快照（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String constraints;

    /** 本版应用的 operations 留痕（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String patch;

    /** 来源：extract/followup/manual/feedback。 */
    private String source;

    private LocalDateTime createdAt;
}
