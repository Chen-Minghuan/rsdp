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
    /** 成本单价快照（出厂价，AES 加密，内部可见） */
    private BigDecimal originalPrice;
    /** 到手单价快照（标准售价 × 折扣率） */
    private BigDecimal finalPrice;
    /** 行级改价（非空时优先于 finalPrice 作为到手单价，仅 PENDING 可编辑） */
    private BigDecimal adjustPrice;
    /** 生效到手单价（adjustPrice 优先，其次 finalPrice） */
    private BigDecimal effectivePrice;
    /** 标准售价快照（明文，对客户可见） */
    private BigDecimal listPrice;
    /** 售价是否低于成本（清库存场景提示） */
    private boolean belowCost;
    /** 空间字典码（订单空间快照，可空） */
    private String spaceTag;
    /** 空间显示名（场景字典名；码已删时为码原文；无空间为 null） */
    private String spaceTagName;
    private String factoryCode;
    /** 小计（生效到手单价 × 数量） */
    private BigDecimal subtotal;
}
