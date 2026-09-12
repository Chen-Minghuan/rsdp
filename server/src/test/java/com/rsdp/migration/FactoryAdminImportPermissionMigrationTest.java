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
 * {@link FactoryAdminImportPermissionMigration} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class FactoryAdminImportPermissionMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private FactoryAdminImportPermissionMigration migration;

    @Test
    void grantsProductImportToFactoryAdminIdempotently() {
        when(jdbcTemplate.update(anyString())).thenReturn(1);

        migration.execute();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(sql.capture());
        assertTrue(sql.getValue().contains("FACTORY_ADMIN"));
        assertTrue(sql.getValue().contains("product:import"));
        assertTrue(sql.getValue().contains("ON CONFLICT DO NOTHING"));
    }

    @Test
    void skipsSilentlyWhenAlreadyGranted() {
        when(jdbcTemplate.update(anyString())).thenReturn(0);

        assertDoesNotThrow(() -> migration.execute());

        verify(jdbcTemplate).update(anyString());
    }
}
