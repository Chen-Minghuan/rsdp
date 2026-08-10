package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.DashboardSummaryResponse;
import com.rsdp.service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端工作台统计带接口（需登录，SecurityConfig 限定 ADMIN/EDITOR 角色）。
 */
@RestController
@RequestMapping("/api/v1/dashboard")
@RequiredArgsConstructor
@Validated
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * 统计带聚合：RSPU 总数 / RSKU 总数 / AI 识别通过率 / 本月订单额 / 今日留资数。
     *
     * @return 统计带数据
     */
    @GetMapping("/summary")
    public Result<DashboardSummaryResponse> summary() {
        return Result.ok(dashboardService.summary());
    }
}
