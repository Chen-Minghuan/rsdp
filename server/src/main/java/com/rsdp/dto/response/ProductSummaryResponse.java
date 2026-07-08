package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品列表项响应。
 */
@Data
public class ProductSummaryResponse {

    private String rspuId;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String colorPrimaryName;
    private String status;
    private String reviewStatus;
    private String aestheticsConfidence;
    private String productLevel;
    private String primaryImageUrl;
    private List<String> factoryCodes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
