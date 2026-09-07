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
}
