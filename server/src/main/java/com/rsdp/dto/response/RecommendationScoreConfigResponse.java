package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 推荐打分配置响应。
 */
@Data
public class RecommendationScoreConfigResponse {

    private String configId;
    private String configKey;
    private String name;
    private String description;
    private Map<String, BigDecimal> weights;
    private Boolean isDefault;
    private Boolean isActive;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
