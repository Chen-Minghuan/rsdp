package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * RSKU 报价响应。
 */
@Data
public class RskuResponse {

    private String rskuId;
    private String rspuId;
    private String variantId;
    private String factoryCode;
    private String factoryName;
    private List<String> factoryCapableLevels;
    private String factorySku;
    private BigDecimal factoryPrice;
    private String priceBand;
    private String productLevel;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
    private String quoteConfidence;
    private String reviewStatus;
    private LocalDate priceUpdated;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
