package com.rsdp.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 风格搭配公式各维度得分。
 */
@Data
@Builder
public class FormulaScore {

    /**
     * 维度名称，如 compatible-material、must_have 等。
     */
    private String dimension;

    /**
     * 该维度得分，0~1 之间。
     */
    private BigDecimal score;

    /**
     * 维度权重。
     */
    private BigDecimal weight;

    /**
     * 命中值列表。
     */
    private String matchedValues;

    /**
     * 说明。
     */
    private String reason;
}
