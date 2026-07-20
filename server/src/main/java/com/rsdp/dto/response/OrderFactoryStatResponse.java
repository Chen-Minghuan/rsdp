package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单统计-工厂维度统计项（按 design_order_item.factory_code 聚合）。
 */
@Data
public class OrderFactoryStatResponse {

    private String factoryCode;
    private String factoryName;
    /** 订单数（distinct order_id） */
    private Long orderCount;
    /** 总件数（quantity 合计） */
    private Long totalQuantity;
    /** 总到手金额（到手单价 × 数量 合计） */
    private BigDecimal totalAmount;
}
