package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建 AI 推荐候选请求。
 */
@Data
public class SchemeCandidateCreateRequest {

    /** 推荐请求 ID。 */
    @NotBlank(message = "推荐请求 ID 不能为空")
    private String recommendRequestId;

    /** RSPU ID。 */
    @NotBlank(message = "RSPU ID 不能为空")
    private String rspuId;

    /** RSKU ID（可选）。 */
    private String rskuId;

    /** 推荐得分。 */
    @NotNull(message = "推荐得分不能为空")
    private BigDecimal score;

    /** AI 推荐理由。 */
    private String aiReason;

    /** 匹配因子。 */
    private Map<String, Object> matchFactors;
}
