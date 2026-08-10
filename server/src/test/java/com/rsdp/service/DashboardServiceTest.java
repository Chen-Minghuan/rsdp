package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.DashboardSummaryResponse;
import com.rsdp.entity.DesignOrder;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.DesignOrderMapper;
import com.rsdp.mapper.PlatformLeadMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link DashboardService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @Mock
    private AiRecognitionMapper aiRecognitionMapper;

    @Mock
    private DesignOrderMapper designOrderMapper;

    @Mock
    private PlatformLeadMapper platformLeadMapper;

    @InjectMocks
    private DashboardService dashboardService;

    @Test
    void summary_shouldAggregateAllMetrics() {
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(1286L);
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(3514L);
        // ai_recognition 先查 done 再查 failed
        when(aiRecognitionMapper.selectCount(any(QueryWrapper.class)))
            .thenReturn(96L)
            .thenReturn(4L);
        DesignOrder order = new DesignOrder();
        order.setFinalTotalPrice(new BigDecimal("100000.00"));
        DesignOrder order2 = new DesignOrder();
        order2.setFinalTotalPrice(new BigDecimal("84000.00"));
        when(designOrderMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(order, order2));
        when(platformLeadMapper.selectCount(any(QueryWrapper.class))).thenReturn(12L);

        DashboardSummaryResponse summary = dashboardService.summary();

        assertThat(summary.getRspuTotal()).isEqualTo(1286L);
        assertThat(summary.getRskuTotal()).isEqualTo(3514L);
        assertThat(summary.getAiPassRate()).isEqualByComparingTo("96.0");
        assertThat(summary.getMonthOrderAmount()).isEqualByComparingTo("184000.00");
        assertThat(summary.getTodayLeadCount()).isEqualTo(12L);
    }

    @Test
    void summary_noRecognition_shouldReturnNullPassRate() {
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);
        when(aiRecognitionMapper.selectCount(any(QueryWrapper.class)))
            .thenReturn(0L)
            .thenReturn(0L);
        when(designOrderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(platformLeadMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.summary();

        assertThat(summary.getAiPassRate()).isNull();
        assertThat(summary.getMonthOrderAmount()).isEqualByComparingTo("0");
    }

    @Test
    void summary_orderWithNullPrice_shouldTreatedAsZero() {
        when(rspuMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(rskuSupplyMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);
        when(aiRecognitionMapper.selectCount(any(QueryWrapper.class)))
            .thenReturn(1L)
            .thenReturn(0L);
        DesignOrder order = new DesignOrder();
        order.setFinalTotalPrice(null);
        when(designOrderMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(order));
        when(platformLeadMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        DashboardSummaryResponse summary = dashboardService.summary();

        assertThat(summary.getMonthOrderAmount()).isEqualByComparingTo("0");
        assertThat(summary.getAiPassRate()).isEqualByComparingTo("100.0");
    }
}
