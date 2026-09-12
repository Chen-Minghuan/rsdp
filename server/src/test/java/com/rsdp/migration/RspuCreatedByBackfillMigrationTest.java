package com.rsdp.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RspuCreatedByBackfillMigration} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RspuCreatedByBackfillMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private RspuCreatedByBackfillMigration migration;

    @Test
    void backfillsCreatedByFromAuditLogIdempotently() {
        when(jdbcTemplate.update(anyString())).thenReturn(42);

        migration.execute();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture());
        // 仅处理 NULL 行（幂等）、audit_log 首条 CREATE 反查、join sys_user 换 user_id
        assertTrue(sql.getValue().contains("m.created_by IS NULL"));
        assertTrue(sql.getValue().contains("audit_log"));
        assertTrue(sql.getValue().contains("'CREATE'"));
        assertTrue(sql.getValue().contains("sys_user"));
    }

    @Test
    void skipsSilentlyWhenNothingToBackfill() {
        when(jdbcTemplate.update(anyString())).thenReturn(0);

        assertDoesNotThrow(() -> migration.execute());

        verify(jdbcTemplate).update(anyString());
    }
}
