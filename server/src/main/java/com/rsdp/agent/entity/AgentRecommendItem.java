package com.rsdp.agent.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 营销 Agent 推荐条目实体（agent_recommend_item，V10）。
 *
 * <p>snapshot 存产品事实快照（名称/颜色/材质/retailPrice/尺寸等），
 * reason 存推荐理由（highlights + evidenceRefs）；(batch_id, rank) 唯一。</p>
 */
@Data
@TableName("agent_recommend_item")
public class AgentRecommendItem {

    @TableId
    private String itemId;

    private String batchId;

    private String rspuId;

    /** 批次内排名。 */
    private Integer rank;

    private BigDecimal rankScore;

    /** 产品事实快照：名称/颜色/材质/retailPrice/尺寸等（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshot;

    /** 推荐理由：highlights + evidenceRefs（JSON）。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String reason;

    /** 配套分组标签（品类码 TB/FS/FC 等），主体选品为空（V11）。 */
    private String groupTag;

    private LocalDateTime createdAt;
}
