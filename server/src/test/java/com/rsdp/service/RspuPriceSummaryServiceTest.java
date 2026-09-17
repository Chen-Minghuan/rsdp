package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.entity.RspuPriceSummary;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RspuPriceSummaryMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link RspuPriceSummaryService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuPriceSummaryServiceTest {

    @Mock
    private RspuPriceSummaryMapper rspuPriceSummaryMapper;

    @Mock
    private RskuSupplyMapper rskuSupplyMapper;

    @InjectMocks
    private RspuPriceSummaryService service;

    private RskuSupply rskuWithPrice(String price) {
        RskuSupply rsku = new RskuSupply();
        rsku.setFactoryPrice(price != null ? new BigDecimal(price) : null);
        return rsku;
    }

    @Test
    void recalculateComputesMinMaxAndCount() {
        // 查询已在 DB 侧按新口径过滤（factory_price 非空且 status='active'），
        // 返回行全部为有效报价行：count = 行数，min/max 全量参与
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rskuWithPrice("1200.00"),
            rskuWithPrice("800.50"),
            rskuWithPrice("2500.00")
        ));

        service.recalculate("RSPU-1");

        ArgumentCaptor<RspuPriceSummary> captor = ArgumentCaptor.forClass(RspuPriceSummary.class);
        verify(rspuPriceSummaryMapper).upsert(captor.capture());
        RspuPriceSummary summary = captor.getValue();
        assertThat(summary.getRspuId()).isEqualTo("RSPU-1");
        assertThat(summary.getMinFactoryPrice()).isEqualByComparingTo("800.50");
        assertThat(summary.getMaxFactoryPrice()).isEqualByComparingTo("2500.00");
        assertThat(summary.getActiveRskuCount()).isEqualTo(3);
        assertThat(summary.getUpdatedAt()).isNotNull();
    }

    @Test
    void recalculateQueryFiltersOnlyPricedActiveRskus() {
        // 口径（f39976e 修正）：active_rsku_count 与 min/max 只统计
        // 「factory_price 非空」的 RSKU 行——rsku_supply 无 status 列，
        // 不得再按 status 过滤（否则 SQL 报错），NULL 价行在 DB 查询侧即被排除
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.recalculate("RSPU-1");

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(rskuSupplyMapper).selectList(captor.capture());
        String sqlSegment = captor.getValue().getSqlSegment();
        assertThat(sqlSegment).contains("rspu_id");
        assertThat(sqlSegment).doesNotContain("status");
        assertThat(sqlSegment).contains("factory_price IS NOT NULL");
    }

    @Test
    void recalculateWithNoActiveRskuUpsertsZeroCountRow() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.recalculate("RSPU-EMPTY");

        ArgumentCaptor<RspuPriceSummary> captor = ArgumentCaptor.forClass(RspuPriceSummary.class);
        verify(rspuPriceSummaryMapper).upsert(captor.capture());
        RspuPriceSummary summary = captor.getValue();
        assertThat(summary.getMinFactoryPrice()).isNull();
        assertThat(summary.getMaxFactoryPrice()).isNull();
        assertThat(summary.getActiveRskuCount()).isEqualTo(0);
    }

    @Test
    void recalculateIgnoresBlankRspuId() {
        service.recalculate(" ");
        verifyNoInteractions(rskuSupplyMapper, rspuPriceSummaryMapper);
    }

    @Test
    void batchSummariesReturnsEmptyMapForEmptyInput() {
        assertThat(service.batchSummaries(List.of())).isEmpty();
        assertThat(service.batchSummaries(null)).isEmpty();
        verifyNoInteractions(rspuPriceSummaryMapper);
    }

    @Test
    void batchSummariesMapsByRspuId() {
        RspuPriceSummary s1 = new RspuPriceSummary();
        s1.setRspuId("RSPU-1");
        s1.setMinFactoryPrice(new BigDecimal("100"));
        s1.setActiveRskuCount(2);
        when(rspuPriceSummaryMapper.selectBatchIds(List.of("RSPU-1", "RSPU-2")))
            .thenReturn(List.of(s1));

        Map<String, RspuPriceSummary> result = service.batchSummaries(List.of("RSPU-1", "RSPU-2"));

        assertThat(result).containsOnlyKeys("RSPU-1");
        assertThat(result.get("RSPU-1").getMinFactoryPrice()).isEqualByComparingTo("100");
    }

    @Test
    void deferralScopeCollectsAndRecalculatesEachRspuOnce() {
        // 3.4：延迟重算作用域——作用域内 recalculate 只登记去重不查库，
        // close 时每 RSPU 只重算一次（同一 RSPU 多次 RSKU 写入合并为一次投影重算）
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (RspuPriceSummaryService.DeferralScope scope = service.openDeferralScope()) {
            service.recalculate("RSPU-1");
            service.recalculate("RSPU-1");
            service.recalculate("RSPU-1");
            service.recalculate("RSPU-2");
            service.recalculate(" ");
            // 作用域内不触库
            verifyNoInteractions(rskuSupplyMapper, rspuPriceSummaryMapper);
        }

        // close 后每 RSPU 恰好重算一次
        ArgumentCaptor<RspuPriceSummary> captor = ArgumentCaptor.forClass(RspuPriceSummary.class);
        verify(rspuPriceSummaryMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(RspuPriceSummary::getRspuId)
            .containsExactlyInAnyOrder("RSPU-1", "RSPU-2");
    }

    @Test
    void deferralScopeCloseWithoutCollectedIdsDoesNothing() {
        try (RspuPriceSummaryService.DeferralScope ignored = service.openDeferralScope()) {
            // 无 recalculate 调用
        }
        verifyNoInteractions(rskuSupplyMapper, rspuPriceSummaryMapper);
    }

    @Test
    void recalculateOutsideDeferralScopeHitsDbImmediately() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.recalculate("RSPU-1");

        verify(rspuPriceSummaryMapper).upsert(any(RspuPriceSummary.class));
    }

    @Test
    void nestedDeferralScopeMergesIntoOuter() {
        // 嵌套作用域：内层 close 不触库，并入外层，外层 close 统一重算一次
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (RspuPriceSummaryService.DeferralScope outer = service.openDeferralScope()) {
            service.recalculate("RSPU-1");
            try (RspuPriceSummaryService.DeferralScope inner = service.openDeferralScope()) {
                service.recalculate("RSPU-1");
                service.recalculate("RSPU-2");
            }
            // 内层 close 后不触库
            verifyNoInteractions(rskuSupplyMapper, rspuPriceSummaryMapper);
        }

        ArgumentCaptor<RspuPriceSummary> captor = ArgumentCaptor.forClass(RspuPriceSummary.class);
        verify(rspuPriceSummaryMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(RspuPriceSummary::getRspuId)
            .containsExactlyInAnyOrder("RSPU-1", "RSPU-2");
    }
}
