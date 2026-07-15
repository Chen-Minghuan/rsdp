package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.OrderCreateRequest;
import com.rsdp.dto.request.OrderStatusRequest;
import com.rsdp.dto.request.OrderUpdateRequest;
import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderListResponse;
import com.rsdp.dto.response.OrderResponse;
import com.rsdp.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计订单接口。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;

    /**
     * 由方案生成订单（价格快照 × 全局折扣率）。
     *
     * @param request 创建请求
     * @return 订单详情
     */
    @PostMapping
    public Result<OrderDetailResponse> create(@RequestBody @Valid OrderCreateRequest request) {
        return Result.ok(orderService.create(request));
    }

    /**
     * 分页查询订单列表（含各状态计数）。
     *
     * @param status 状态筛选（可选）
     * @param page   页码
     * @param size   每页条数
     * @return 分页与状态计数
     */
    @GetMapping
    public Result<OrderListResponse> list(
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(orderService.list(status, page, size));
    }

    /**
     * 查询订单详情（含明细快照）。
     *
     * @param orderId 订单 ID
     * @return 订单详情
     */
    @GetMapping("/{orderId}")
    public Result<OrderDetailResponse> detail(
        @PathVariable @NotBlank(message = "订单 ID 不能为空") String orderId) {
        return Result.ok(orderService.detail(orderId));
    }

    /**
     * 更新订单收件信息与备注（仅 PENDING 可改）。
     *
     * @param orderId 订单 ID
     * @param request 更新请求
     * @return 更新后的订单
     */
    @PutMapping("/{orderId}")
    public Result<OrderResponse> update(
        @PathVariable @NotBlank(message = "订单 ID 不能为空") String orderId,
        @RequestBody @Valid OrderUpdateRequest request) {
        return Result.ok(orderService.update(orderId, request));
    }

    /**
     * 订单状态迁移（状态机校验）。
     *
     * @param orderId 订单 ID
     * @param request 状态迁移请求
     * @return 更新后的订单
     */
    @PutMapping("/{orderId}/status")
    public Result<OrderResponse> updateStatus(
        @PathVariable @NotBlank(message = "订单 ID 不能为空") String orderId,
        @RequestBody @Valid OrderStatusRequest request) {
        return Result.ok(orderService.updateStatus(orderId, request.getStatus()));
    }
}
