package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 报价单项。
 */
@Data
public class QuoteItemResponse {

    private String rspuId;
    private String rspuName;
    private String primaryImageUrl;

    private String rskuId;
    private String factoryCode;
    private String factoryName;
    private String factorySku;

    private BigDecimal factoryPrice;
    private String priceBand;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
}
