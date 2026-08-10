package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.DashboardSummaryResponse;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.DesignOrder;
import com.rsdp.entity.PlatformLead;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.PlatformLeadMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理端工作台统计带聚合服务（GET /api/v1/dashboard/summary）。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final DesignOrderMapper designOrderMapper;
    private final PlatformLeadMapper platformLeadMapper;

    /**
     * 统计带聚合：RSPU 总数 / RSKU 总数 / AI 识别通过率 / 本月订单额 / 今日留资数。
     *
     * @return 统计带数据
     */
    public DashboardSummaryResponse summary() {
        DashboardSummaryResponse response = new DashboardSummaryResponse();
        response.setRspuTotal(rspuMapper.selectCount(new QueryWrapper<RspuMaster>()));
        response.setRskuTotal(rskuSupplyMapper.selectCount(new QueryWrapper<RskuSupply>()));
        response.setAiPassRate(aiPassRate());
        response.setMonthOrderAmount(monthOrderAmount());
        response.setTodayLeadCount(todayLeadCount());
        return response;
    }

    /**
     * AI 识别通过率 = done / (done + failed) × 100（百分比，保留 1 位小数；无记录返回 null）。
     */
    private BigDecimal aiPassRate() {
        long done = aiRecognitionMapper.selectCount(
            new QueryWrapper<AiRecognition>().eq("status", "done"));
        long failed = aiRecognitionMapper.selectCount(
            new QueryWrapper<AiRecognition>().eq("status", "failed"));
        long total = done + failed;
        if (total == 0) {
            return null;
        }
        return BigDecimal.valueOf(done * 100.0 / total).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * 本月订单额：到手价合计，不含已取消订单。
     *
     * <p>final_total_price 为 AES 加密列，无法 SQL 聚合，查实体后 Java 内存求和
     * （与 OrderStatisticsService 同一模式）。</p>
     */
    private BigDecimal monthOrderAmount() {
        LocalDate monthStart = LocalDate.now().withDayOfMonth(1);
        List<DesignOrder> orders = designOrderMapper.selectList(new QueryWrapper<DesignOrder>()
            .ne("status", OrderService.STATUS_CANCELLED)
            .ge("created_at", monthStart.atStartOfDay()));
        return orders.stream()
            .map(order -> order.getFinalTotalPrice() != null ? order.getFinalTotalPrice() : BigDecimal.ZERO)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * 今日新增留资线索数。
     */
    private Long todayLeadCount() {
        return platformLeadMapper.selectCount(new QueryWrapper<PlatformLead>()
            .ge("created_at", LocalDate.now().atStartOfDay()));
    }
}
