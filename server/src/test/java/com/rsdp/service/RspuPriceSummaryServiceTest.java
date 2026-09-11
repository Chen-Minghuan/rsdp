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
    void recalculateComputesMinMaxAndCountIgnoringNullPrices() {
        when(rskuSupplyMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(
            rskuWithPrice("1200.00"),
            rskuWithPrice(null),
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
        // 计数含价格为 NULL 的有效报价（与列表"报价×N"旧口径一致）
        assertThat(summary.getActiveRskuCount()).isEqualTo(4);
        assertThat(summary.getUpdatedAt()).isNotNull();
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
