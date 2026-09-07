package com.rsdp.migration;

import com.rsdp.config.properties.PriceSummaryBackfillProperties;
import com.rsdp.service.RspuPriceSummaryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PriceSummaryBackfillMigration} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PriceSummaryBackfillMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private RspuPriceSummaryService rspuPriceSummaryService;

    @Mock
    private PriceSummaryBackfillProperties properties;

    @InjectMocks
    private PriceSummaryBackfillMigration migration;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(properties.getBatchSize()).thenReturn(100);
    }

    @Test
    void skipsWhenDisabled() {
        when(properties.isEnabled()).thenReturn(false);

        migration.run();

        verify(jdbcTemplate, never()).queryForList(anyString(), eq(String.class), anyInt(), anyInt());
        verify(rspuPriceSummaryService, never()).recalculate(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void recalculatesEveryRspuWhenEnabled() {
        when(properties.isEnabled()).thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyInt(), anyInt()))
            .thenReturn(List.of("RSPU-1", "RSPU-2"))
            .thenReturn(List.of());

        migration.run();

        verify(rspuPriceSummaryService).recalculate("RSPU-1");
        verify(rspuPriceSummaryService).recalculate("RSPU-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void continuesWhenSingleRspuFails() {
        when(properties.isEnabled()).thenReturn(true);
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyInt(), anyInt()))
            .thenReturn(List.of("RSPU-BAD", "RSPU-OK"))
            .thenReturn(List.of());
        org.mockito.Mockito.doThrow(new RuntimeException("boom"))
            .when(rspuPriceSummaryService).recalculate("RSPU-BAD");

        migration.run();

        verify(rspuPriceSummaryService).recalculate("RSPU-OK");
    }
}
