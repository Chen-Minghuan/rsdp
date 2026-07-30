package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品列表项响应。
 */
@Data
public class ProductSummaryResponse {

    private String rspuId;
    private String rspuCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String productName;
    private String colorPrimaryName;
    private String status;
    private String reviewStatus;
    private String aestheticsConfidence;
    private String productLevel;
    private BigDecimal minFactoryPrice;
    private String primaryImageUrl;
    private List<String> factoryCodes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
