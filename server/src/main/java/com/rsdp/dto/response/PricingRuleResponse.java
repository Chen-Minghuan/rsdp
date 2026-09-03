package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 品类加价规则响应。
 */
@Data
public class PricingRuleResponse {

    private String ruleId;
    private String categoryCode;
    /** 品类名称（category_dict dict_type=category，未匹配时为空） */
    private String categoryName;
    private BigDecimal markupMultiplier;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
