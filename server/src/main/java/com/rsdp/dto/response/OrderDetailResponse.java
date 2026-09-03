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

    /** 售价低于成本的订单级警告（清库存场景提示；无则 null） */
    private String priceWarning;
}
