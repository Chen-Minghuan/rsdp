package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 品类加价规则 upsert 请求。
 */
@Data
public class PricingRuleUpsertRequest {

    /** 品类加价倍率（必须 &gt; 0） */
    @NotNull(message = "加价倍率不能为空")
    private BigDecimal markupMultiplier;

    /** 备注（可空） */
    private String remark;
}
