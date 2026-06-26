package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 搭配方案单项。
 */
@Data
public class SchemeItemResponse {

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
}
