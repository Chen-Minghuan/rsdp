package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 搭配方案项响应。
 */
@Data
public class SchemeItemResponse {

    private Long schemeItemId;
    private String rspuId;
    private String rspuName;
    private String primaryImageUrl;

    private String rskuId;
    private String factoryCode;
    private String factoryName;
    private String factorySku;

    private BigDecimal factoryPrice;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer sortOrder;
}
