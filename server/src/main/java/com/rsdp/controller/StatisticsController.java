package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.FactoryStatResponse;
import com.rsdp.dto.response.StatisticsOverviewResponse;
import com.rsdp.dto.response.TrendItemResponse;
import com.rsdp.service.StatisticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营统计接口。数据按当前用户隔离（非 ADMIN 仅统计自己的方案/项目）。
 */
@RestController
@RequestMapping("/api/v1/statistics")
@RequiredArgsConstructor
public class StatisticsController {

    private final StatisticsService statisticsService;

    /**
     * 统计总览。
     *
     * @return 总览数据
     */
    @GetMapping("/overview")
    public Result<StatisticsOverviewResponse> overview() {
        return Result.ok(statisticsService.overview());
    }

    /**
     * 按月趋势。
     *
     * @param months 月数（1~24，默认 6）
     * @return 趋势列表
     */
    @GetMapping("/trends")
    public Result<List<TrendItemResponse>> trends(@RequestParam(defaultValue = "6") int months) {
        return Result.ok(statisticsService.trends(months));
    }

    /**
     * 工厂维度方案金额 TOP10。
     *
     * @return 工厂统计列表
     */
    @GetMapping("/factories")
    public Result<List<FactoryStatResponse>> factories() {
        return Result.ok(statisticsService.factories());
    }
}
