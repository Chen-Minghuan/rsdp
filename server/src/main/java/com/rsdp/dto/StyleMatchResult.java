package com.rsdp.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 风格匹配评分结果。
 */
@Data
@Builder
public class StyleMatchResult {

    /**
     * 匹配的风格编码。
     */
    private String styleCode;

    /**
     * 整体得分，0~1 之间。
     */
    private BigDecimal overallScore;

    /**
     * 基于得分映射的置信度：high / mid / low。
     */
    private String confidence;

    /**
     * 元素级匹配明细：命中 / 缺失 / 禁忌。
     */
    @Builder.Default
    private List<ElementMatchDetail> elementMatches = new ArrayList<>();

    /**
     * 公式各维度得分。
     */
    @Builder.Default
    private List<FormulaScore> formulaScores = new ArrayList<>();

    /**
     * 评分说明/建议。
     */
    private String reason;

    /**
     * 是否触发了禁忌项，导致风格可能不成立。
     */
    private boolean hasAvoidHit;
}
