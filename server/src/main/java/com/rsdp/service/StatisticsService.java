package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.FactoryStatResponse;
import com.rsdp.dto.response.StatisticsOverviewResponse;
import com.rsdp.dto.response.TrendItemResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.Project;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 运营统计服务：基于方案/项目的只读聚合，非 ADMIN 仅统计自己的数据。
 */
@Service
@RequiredArgsConstructor
public class StatisticsService {

    private static final int FACTORY_TOP_LIMIT = 20;

    private final SchemeMapper schemeMapper;
    private final SchemeItemMapper schemeItemMapper;
    private final ProjectMapper projectMapper;
    private final FactoryMasterMapper factoryMasterMapper;

    /**
     * 统计总览：方案数/金额、项目数、平均方案金额、本月新增。
     *
     * @return 总览数据
     */
    public StatisticsOverviewResponse overview() {
        QueryWrapper<Scheme> scope = schemeScope();
        Long schemeCount = schemeMapper.selectCount(scope);
        BigDecimal totalAmount = sumSchemeAmount();

        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        Long monthNewSchemes = schemeMapper.selectCount(schemeScope()
            .ge("created_at", monthStart.atStartOfDay()));

        QueryWrapper<Project> projectScope = new QueryWrapper<>();
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            projectScope.eq("owner_id", SecurityOperatorContext.currentUserId());
        }
        Long projectCount = projectMapper.selectCount(projectScope);

        StatisticsOverviewResponse response = new StatisticsOverviewResponse();
        response.setSchemeCount(schemeCount);
        response.setTotalAmount(totalAmount);
        response.setProjectCount(projectCount);
        response.setAvgSchemeAmount(schemeCount != null && schemeCount > 0
            ? totalAmount.divide(BigDecimal.valueOf(schemeCount), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO);
        response.setMonthNewSchemes(monthNewSchemes);
        return response;
    }

    /**
     * 按月趋势：最近 N 个月的方案数与金额（缺失月份补零）。
     *
     * @param months 月数（1~24）
     * @return 趋势列表
     */
    public List<TrendItemResponse> trends(int months) {
        int clamped = Math.max(1, Math.min(months, 24));
        YearMonth start = YearMonth.now().minusMonths(clamped - 1);

        List<Map<String, Object>> rows = schemeMapper.selectMaps(schemeScope()
            .select("to_char(created_at, 'YYYY-MM') AS month",
                "COUNT(*) AS scheme_count",
                "COALESCE(SUM(total_price), 0) AS total_amount")
            .ge("created_at", start.atDay(1).atStartOfDay())
            .groupBy("to_char(created_at, 'YYYY-MM')"));

        Map<String, Map<String, Object>> rowMap = rows.stream()
            .collect(Collectors.toMap(r -> (String) r.get("month"), r -> r, (a, b) -> a));

        List<TrendItemResponse> result = new ArrayList<>();
        for (YearMonth ym = start; !ym.isAfter(YearMonth.now()); ym = ym.plusMonths(1)) {
            TrendItemResponse item = new TrendItemResponse();
            item.setMonth(ym.toString());
            Map<String, Object> row = rowMap.get(ym.toString());
            item.setSchemeCount(row != null ? ((Number) row.get("scheme_count")).longValue() : 0L);
            item.setTotalAmount(row != null ? toBigDecimal(row.get("total_amount")) : BigDecimal.ZERO);
            result.add(item);
        }
        return result;
    }

    /**
     * 工厂维度方案金额 TOP20（按方案项出厂价 × 数量聚合）。
     * 仅统计最近 N 个月内的活跃方案，避免数据量无限增长。
     *
     * @param months 统计月数（1~24）
     * @return 工厂统计列表
     */
    public List<FactoryStatResponse> factories(int months) {
        int clampedMonths = Math.max(1, Math.min(months, 24));
        LocalDate startDate = LocalDate.now().minusMonths(clampedMonths).withDayOfMonth(1);
        List<Scheme> schemes = schemeMapper.selectList(schemeScope()
            .ge("created_at", startDate.atStartOfDay()));
        if (schemes.isEmpty()) {
            return List.of();
        }
        List<String> schemeIds = schemes.stream().map(Scheme::getSchemeId).toList();
        List<SchemeItem> items = schemeItemMapper.selectList(
            new QueryWrapper<SchemeItem>().in("scheme_id", schemeIds));

        Map<String, BigDecimal> amountByFactory = new HashMap<>();
        Map<String, Integer> countByFactory = new HashMap<>();
        for (SchemeItem item : items) {
            if (item.getFactoryCode() == null) {
                continue;
            }
            int quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
            BigDecimal subtotal = item.getFactoryPrice() != null
                ? item.getFactoryPrice().multiply(BigDecimal.valueOf(quantity))
                : BigDecimal.ZERO;
            amountByFactory.merge(item.getFactoryCode(), subtotal, BigDecimal::add);
            countByFactory.merge(item.getFactoryCode(), 1, Integer::sum);
        }

        Map<String, String> factoryNames = factoryMasterMapper.selectList(
                new QueryWrapper<FactoryMaster>().in("factory_code", amountByFactory.keySet()))
            .stream()
            .collect(Collectors.toMap(FactoryMaster::getFactoryCode, FactoryMaster::getFactoryName, (a, b) -> a));

        return amountByFactory.entrySet().stream()
            .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
            .limit(FACTORY_TOP_LIMIT)
            .map(entry -> {
                FactoryStatResponse stat = new FactoryStatResponse();
                stat.setFactoryCode(entry.getKey());
                stat.setFactoryName(factoryNames.getOrDefault(entry.getKey(), entry.getKey()));
                stat.setTotalAmount(entry.getValue());
                stat.setItemCount(countByFactory.getOrDefault(entry.getKey(), 0));
                return stat;
            })
            .toList();
    }

    /**
     * 当前用户可见的方案范围（非 ADMIN 仅自己的方案）。
     *
     * @return 方案查询条件
     */
    private QueryWrapper<Scheme> schemeScope() {
        QueryWrapper<Scheme> wrapper = new QueryWrapper<Scheme>().eq("status", "active");
        if (!SecurityOperatorContext.isCurrentUserAdmin()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUsername());
        }
        return wrapper;
    }

    private BigDecimal sumSchemeAmount() {
        List<Map<String, Object>> rows = schemeMapper.selectMaps(schemeScope()
            .select("COALESCE(SUM(total_price), 0) AS total"));
        return rows.isEmpty() ? BigDecimal.ZERO : toBigDecimal(rows.get(0).get("total"));
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal bd ? bd : new BigDecimal(value.toString());
    }
}
