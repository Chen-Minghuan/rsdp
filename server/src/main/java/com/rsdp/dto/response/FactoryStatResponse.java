package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 工厂维度统计项。
 */
@Data
public class FactoryStatResponse {

    private String factoryCode;
    private String factoryName;
    /** 方案项金额合计（出厂价 × 数量） */
    private BigDecimal totalAmount;
    /** 方案项数量 */
    private Integer itemCount;
}
