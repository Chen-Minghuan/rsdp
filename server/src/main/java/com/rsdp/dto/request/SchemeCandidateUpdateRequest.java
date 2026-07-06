package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 更新 AI 推荐候选请求。
 */
@Data
public class SchemeCandidateUpdateRequest {

    /** RSKU ID（可选）。 */
    private String rskuId;

    /** 推荐得分。 */
    private BigDecimal score;

    /** AI 推荐理由。 */
    private String aiReason;

    /** 匹配因子。 */
    private Map<String, Object> matchFactors;

    /** 状态：pending / accepted / rejected。 */
    @Size(max = 16, message = "状态不能超过 16 个字符")
    private String status;
}
