package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单统计-产品维度统计项（按 design_order_item.rspu_id 聚合）。
 */
@Data
public class OrderProductStatResponse {

    private String rspuId;
    /** 商品名（取订单明细快照，下单时点口径） */
    private String productName;
    /** 商品图 ID（取订单明细快照） */
    private String imageId;
    /** 总件数（quantity 合计） */
    private Long totalQuantity;
    /** 总到手金额（到手单价 × 数量 合计） */
    private BigDecimal totalAmount;
}
