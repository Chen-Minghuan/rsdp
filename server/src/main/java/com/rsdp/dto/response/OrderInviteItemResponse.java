package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 邀请页订单明细视图（仅到手价，绝不泄露出厂价与工厂信息）。
 */
@Data
public class OrderInviteItemResponse {

    private String productName;
    private String model;
    private String imageId;
    private Integer quantity;
    /** 到手单价 */
    private BigDecimal finalPrice;
    /** 小计（到手单价 × 数量） */
    private BigDecimal subtotal;
    /** 空间字典码（订单空间快照，可空；空间信息仅作分组展示，不含敏感信息） */
    private String spaceTag;
    /** 空间显示名（场景字典名；码已删时为码原文；无空间为 null） */
    private String spaceTagName;
}
