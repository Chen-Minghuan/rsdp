package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.FactoryStatResponse;
import com.rsdp.dto.response.StatisticsOverviewResponse;
import com.rsdp.dto.response.TrendItemResponse;
import com.rsdp.entity.FactoryMaster;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.mapper.FactoryMasterMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StatisticsService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class StatisticsServiceTest {

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private FactoryMasterMapper factoryMasterMapper;

    @InjectMocks
    private StatisticsService statisticsService;

    @Test
    void overviewShouldAggregateAndScopeToOwnerForNonAdmin() {
        when(schemeMapper.selectCount(any(QueryWrapper.class))).thenReturn(5L, 2L);
        when(schemeMapper.selectMaps(any(QueryWrapper.class)))
            .thenReturn(List.of(Map.of("total", new BigDecimal("50000.00"))));
        when(projectMapper.selectCount(any(QueryWrapper.class))).thenReturn(3L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer1");
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            StatisticsOverviewResponse overview = statisticsService.overview();

            assertThat(overview.getSchemeCount()).isEqualTo(5);
            assertThat(overview.getTotalAmount()).isEqualByComparingTo("50000.00");
            assertThat(overview.getAvgSchemeAmount()).isEqualByComparingTo("10000.00");
            assertThat(overview.getMonthNewSchemes()).isEqualTo(2);
            assertThat(overview.getProjectCount()).isEqualTo(3);
        }

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(schemeMapper, atLeastOnce()).selectCount(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("created_by");
    }

    @Test
    void overviewShouldReturnZeroAverageWhenNoSchemes() {
        when(schemeMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L, 0L);
        when(schemeMapper.selectMaps(any(QueryWrapper.class)))
            .thenReturn(List.of(Map.of("total", BigDecimal.ZERO)));
        when(projectMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            StatisticsOverviewResponse overview = statisticsService.overview();

            assertThat(overview.getAvgSchemeAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void trendsShouldFillMissingMonthsWithZero() {
        YearMonth current = YearMonth.now();
        when(schemeMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of(
            Map.of("month", current.toString(),
                "scheme_count", 3L,
                "total_amount", new BigDecimal("9000.00"))
        ));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            List<TrendItemResponse> trends = statisticsService.trends(3);

            assertThat(trends).hasSize(3);
            TrendItemResponse last = trends.get(2);
            assertThat(last.getMonth()).isEqualTo(current.toString());
            assertThat(last.getSchemeCount()).isEqualTo(3);
            assertThat(last.getTotalAmount()).isEqualByComparingTo("9000.00");
            assertThat(trends.get(0).getSchemeCount()).isZero();
            assertThat(trends.get(0).getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    @Test
    void factoriesShouldAggregateAmountsAndSortDesc() {
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        when(schemeMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(scheme));

        SchemeItem item1 = new SchemeItem();
        item1.setFactoryCode("F001");
        item1.setFactoryPrice(new BigDecimal("1000"));
        item1.setQuantity(2);
        SchemeItem item2 = new SchemeItem();
        item2.setFactoryCode("F002");
        item2.setFactoryPrice(new BigDecimal("500"));
        item2.setQuantity(1);
        SchemeItem item3 = new SchemeItem();
        item3.setFactoryCode("F001");
        item3.setFactoryPrice(new BigDecimal("300"));
        item3.setQuantity(1);
        when(schemeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item1, item2, item3));

        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode("F001");
        factory.setFactoryName("佛山家具厂");
        when(factoryMasterMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(factory));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            List<FactoryStatResponse> stats = statisticsService.factories();

            assertThat(stats).hasSize(2);
            assertThat(stats.get(0).getFactoryCode()).isEqualTo("F001");
            assertThat(stats.get(0).getFactoryName()).isEqualTo("佛山家具厂");
            assertThat(stats.get(0).getTotalAmount()).isEqualByComparingTo("2300");
            assertThat(stats.get(0).getItemCount()).isEqualTo(2);
            assertThat(stats.get(1).getFactoryCode()).isEqualTo("F002");
            assertThat(stats.get(1).getTotalAmount()).isEqualByComparingTo("500");
        }
    }
}
