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
    private Integer quantity;
    private BigDecimal subtotal;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer sortOrder;
    /** 空间分区标签（RSPU 首个场景标签名；无标签为 null，前端归入「未分区」） */
    private String spaceTag;
}
