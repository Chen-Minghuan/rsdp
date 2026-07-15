package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.OrderInviteViewResponse;
import com.rsdp.service.OrderInviteService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 订单邀请公开接口（免登录）：客户端通过邀请链接查看到手价视图并确认订单。
 */
@RestController
@RequestMapping("/api/v1/public/orders/invite")
@RequiredArgsConstructor
@Validated
public class PublicOrderController {

    private final OrderInviteService orderInviteService;

    /**
     * 免登录查看邀请页订单视图（仅到手价，不含出厂价/工厂信息）。
     *
     * @param token 邀请 token
     * @return 订单公开视图
     */
    @GetMapping("/{token}")
    public Result<OrderInviteViewResponse> view(
        @PathVariable @NotBlank(message = "邀请 token 不能为空") String token) {
        return Result.ok(orderInviteService.getInviteView(token));
    }

    /**
     * 免登录确认订单（PENDING → CONFIRMED，一次性）。
     *
     * @param token 邀请 token
     * @return 确认后的订单公开视图
     */
    @PostMapping("/{token}/confirm")
    public Result<OrderInviteViewResponse> confirm(
        @PathVariable @NotBlank(message = "邀请 token 不能为空") String token) {
        return Result.ok(orderInviteService.confirmInvite(token));
    }
}
