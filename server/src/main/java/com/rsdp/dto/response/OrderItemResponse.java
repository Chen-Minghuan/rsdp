package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细响应。
 */
@Data
public class OrderItemResponse {

    private Long id;
    private String rspuId;
    private String rskuId;
    private String productName;
    private String model;
    private String imageId;
    private Integer quantity;
    /** 出厂单价快照 */
    private BigDecimal originalPrice;
    /** 到手单价快照 */
    private BigDecimal finalPrice;
    private String factoryCode;
    /** 小计（到手单价 × 数量） */
    private BigDecimal subtotal;
}
