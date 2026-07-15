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
}
