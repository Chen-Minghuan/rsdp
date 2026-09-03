package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 品类级加价倍率实体（pricing_rule）。
 *
 * <p>售价解析链：rspu_master.retail_price 建议销售价（最高优先）→ 成本 × 品类倍率（本表）
 * → 成本 × 全局倍率（sys_config pricing.markup.global，兜底）。</p>
 */
@Data
@TableName("pricing_rule")
public class PricingRule {

    @TableId
    private String ruleId;
    private String categoryCode;
    private BigDecimal markupMultiplier;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
