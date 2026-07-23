package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.OrderCreateRequest;
import com.rsdp.dto.request.OrderItemPriceRequest;
import com.rsdp.dto.request.OrderStatusRequest;
import com.rsdp.dto.request.OrderUpdateRequest;
import com.rsdp.dto.response.InviteTokenResponse;
import com.rsdp.dto.response.OrderDetailResponse;
import com.rsdp.dto.response.OrderListResponse;
import com.rsdp.dto.response.OrderResponse;
import com.rsdp.service.ContractTemplateService;
import com.rsdp.service.OrderInviteService;
import com.rsdp.service.OrderService;
import com.rsdp.service.OrderStatisticsService;
import com.rsdp.exception.BusinessException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * 设计订单接口。
 */
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Validated
public class OrderController {

    private final OrderService orderService;
    private final OrderInviteService orderInviteService;
    private final ContractTemplateService contractTemplateService;
    private final OrderStatisticsService orderStatisticsService;

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
        @RequestParam(defaultValue = "10") @Max(500) long size) {
        return Result.ok(orderService.list(status, page, size));
    }

    /**
     * 订单统计（产品/工厂维度，排除已取消订单，非 ADMIN 仅统计自己创建的订单）。
     * 字面量路径 /statistics 优先于模板路径 /{orderId}，声明顺序保持在 /{orderId} 之前。
     *
     * @param dim  统计维度（product / factory）
     * @param from 起始日期（含，可空，格式 yyyy-MM-dd）
     * @param to   截止日期（含，可空，格式 yyyy-MM-dd）
     * @return 对应维度的统计列表（按总金额降序）
     */
    @GetMapping("/statistics")
    public Result<?> statistics(
        @RequestParam @NotBlank(message = "统计维度不能为空") String dim,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (!OrderStatisticsService.DIM_PRODUCT.equals(dim) && !OrderStatisticsService.DIM_FACTORY.equals(dim)) {
            throw new BusinessException("统计维度仅支持 product / factory");
        }
        return Result.ok(orderStatisticsService.statistics(dim, from, to));
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
     * 订单明细行级改价（仅 PENDING；adjustPrice 为空表示清除改价）。
     *
     * @param orderId 订单 ID
     * @param itemId  明细 ID
     * @param request 改价请求
     * @return 更新后的订单详情
     */
    @PutMapping("/{orderId}/items/{itemId}/price")
    public Result<OrderDetailResponse> adjustItemPrice(
        @PathVariable @NotBlank(message = "订单 ID 不能为空") String orderId,
        @PathVariable @NotNull(message = "明细 ID 不能为空") Long itemId,
        @RequestBody @Valid OrderItemPriceRequest request) {
        return Result.ok(orderService.adjustItemPrice(orderId, itemId, request.getAdjustPrice()));
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

    /**
     * 生成订单邀请链接（重新生成后旧链接立即失效）。
     *
     * @param orderId 订单 ID
     * @return 邀请 token 与过期时间
     */
    @PostMapping("/{orderId}/invite")
    public Result<InviteTokenResponse> createInvite(
        @PathVariable @NotBlank(message = "订单 ID 不能为空") String orderId) {
        return Result.ok(orderInviteService.createInvite(orderId));
    }

    /**
     * 下载采购合同 docx 模板。
     *
     * @param response HTTP 响应
     * @throws IOException 写出失败时抛出
     */
    @GetMapping("/contract-template")
    public void downloadContractTemplate(HttpServletResponse response) throws IOException {
        byte[] content = contractTemplateService.generateContractTemplate();
        response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        response.setHeader("Content-Disposition",
            "attachment; filename*=UTF-8''" + URLEncoder.encode("采购合同模板.docx", StandardCharsets.UTF_8));
        response.getOutputStream().write(content);
    }
}
