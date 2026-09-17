package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 营销 Agent 推荐批次实体（agent_recommend_batch，V10）。
 */
@Data
@TableName("agent_recommend_batch")
public class AgentRecommendBatch {

    @TableId
    private String batchId;

    private String sessionId;

    /** 对应需求版本号。 */
    private Integer versionNo;

    /** 实际下发的检索条件（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String queryCriteria;

    /** 执行推荐的 Skill ID（如 living-room-matching），空=非 Skill 链路（V11）。 */
    private String skillId;

    /** Skill 版本号（V11）。 */
    private String skillVersion;

    /** 批次类型：primary（主体选品）/companion（配套推荐）（V11）。 */
    private String batchType;

    private LocalDateTime createdAt;
}
