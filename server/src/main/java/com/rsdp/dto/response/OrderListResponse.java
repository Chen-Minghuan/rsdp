package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 订单分页列表响应（含各状态计数，供 Tab 徽标）。
 */
@Data
public class OrderListResponse {

    private Long total;
    private Long page;
    private List<OrderResponse> rows;
    /** 各状态订单数（当前用户可见范围内） */
    private Map<String, Long> statusCounts;
}
