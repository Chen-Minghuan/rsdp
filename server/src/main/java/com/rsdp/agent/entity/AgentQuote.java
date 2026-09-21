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
 * 营销 Agent 报价快照实体（agent_quote，V11）。
 *
 * <p>items 行快照只存售价口径（标准售价/价格来源/小计/交期），
 * 绝不存 factoryPrice/cost/margin 任何成本字段；(idempotency_key) 唯一。</p>
 */
@Data
@TableName("agent_quote")
public class AgentQuote {

    @TableId
    private String quoteId;

    private String sessionId;

    /** 行快照（JSON）：confirmedItemId/rspuId/rskuId/名称/主图/数量/标准售价/价格来源/小计/交期。 */
    @JsonRawValue
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String items;

    /** 标准售价合计。 */
    private BigDecimal listTotal;

    /** 生效折扣率快照。 */
    private BigDecimal priceRate;

    /** 预计成交合计（list_total × price_rate）。 */
    private BigDecimal dealTotal;

    /** 状态：generated（已生成）/exported（已导出为方案）。 */
    private String status;

    /** 幂等键，防重复生成。 */
    private String idempotencyKey;

    private String createdBy;

    private LocalDateTime createdAt;
}
