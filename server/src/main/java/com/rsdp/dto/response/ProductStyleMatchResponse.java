package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 产品风格匹配结果响应。
 */
@Data
public class ProductStyleMatchResponse {

    private Long matchId;
    private String rspuId;
    private String styleCode;
    private String styleName;
    private BigDecimal overallScore;
    private String confidence;
    private String elementMatch;
    private String formulaScores;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
