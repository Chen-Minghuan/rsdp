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
    /** 行级改价（非空时优先于 finalPrice 作为到手单价，仅 PENDING 可编辑） */
    private BigDecimal adjustPrice;
    /** 生效到手单价（adjustPrice 优先，其次 finalPrice） */
    private BigDecimal effectivePrice;
    private String factoryCode;
    /** 小计（生效到手单价 × 数量） */
    private BigDecimal subtotal;
}
