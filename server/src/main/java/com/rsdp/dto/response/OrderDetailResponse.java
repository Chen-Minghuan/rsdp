package com.rsdp.dto.response;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

/**
 * 订单详情响应（含明细快照）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OrderDetailResponse extends OrderResponse {

    private List<OrderItemResponse> items;
}
