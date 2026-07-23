package com.rsdp.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细行级改价请求。adjustPrice 为空表示清除改价（回退 原价快照 × 折扣率）。
 */
@Data
public class OrderItemPriceRequest {

    @DecimalMin(value = "0", message = "改价不能小于 0")
    @DecimalMax(value = "99999999.99", message = "改价超出允许范围")
    private BigDecimal adjustPrice;
}
