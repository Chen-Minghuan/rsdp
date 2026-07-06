package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI 推荐候选响应。
 */
@Data
public class SchemeCandidateResponse {

    private String candidateId;
    private String recommendRequestId;
    private String rspuId;
    private String rspuName;
    private String primaryImageUrl;
    private String rskuId;
    private BigDecimal score;
    private String aiReason;
    private Map<String, Object> matchFactors;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
