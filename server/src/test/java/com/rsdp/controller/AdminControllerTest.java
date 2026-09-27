package com.rsdp.controller;

import com.rsdp.dto.SupplierIdentityBackfillReport;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.SupplierConfigurationMigrationService;
import com.rsdp.service.SupplierIdentityBackfillService;
import com.rsdp.service.VectorBackfillService;
import com.rsdp.service.VectorRebuildService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AdminController} 单元测试（供应商身份历史回填触发入口）。
 */
@WebMvcTest(AdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VectorBackfillService vectorBackfillService;

    @MockBean
    private VectorRebuildService vectorRebuildService;

    @MockBean
    private SupplierIdentityBackfillService supplierIdentityBackfillService;

    @MockBean
    private SupplierConfigurationMigrationService supplierConfigurationMigrationService;

    @MockBean(name = "taskExecutor")
    private ThreadPoolTaskExecutor taskExecutor;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void backfillSupplierIdentity_shouldDefaultToDryRun() throws Exception {
        SupplierIdentityBackfillReport report = new SupplierIdentityBackfillReport(
            true, 200, 1, 10, 10, 8, 0, 2, Map.of("MULTIPLE_FACTORY_CANDIDATES", 2), List.of());
        when(supplierIdentityBackfillService.backfill(any())).thenReturn(report);

        mockMvc.perform(post("/api/v1/admin/supplier-identity/backfill"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dryRun").value(true))
            .andExpect(jsonPath("$.data.scanned").value(10))
            .andExpect(jsonPath("$.data.created").value(8))
            .andExpect(jsonPath("$.data.queuedByType.MULTIPLE_FACTORY_CANDIDATES").value(2));

        ArgumentCaptor<SupplierIdentityBackfillService.BackfillOptions> captor =
            ArgumentCaptor.forClass(SupplierIdentityBackfillService.BackfillOptions.class);
        verify(supplierIdentityBackfillService).backfill(captor.capture());
        assertThat(captor.getValue().isDryRun()).isTrue();
        assertThat(captor.getValue().getBatchSize()).isEqualTo(200);
        assertThat(captor.getValue().getSourceSystem()).isNull();
    }

    @Test
    void backfillSupplierIdentity_shouldPassThroughRealRunParams() throws Exception {
        SupplierIdentityBackfillReport report = new SupplierIdentityBackfillReport(
            false, 50, 1, 10, 10, 8, 0, 2, Map.of("MULTIPLE_FACTORY_CANDIDATES", 2), List.of());
        when(supplierIdentityBackfillService.backfill(any())).thenReturn(report);

        mockMvc.perform(post("/api/v1/admin/supplier-identity/backfill")
                .param("dryRun", "false")
                .param("batchSize", "50")
                .param("sourceSystem", "custom-source"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.dryRun").value(false));

        ArgumentCaptor<SupplierIdentityBackfillService.BackfillOptions> captor =
            ArgumentCaptor.forClass(SupplierIdentityBackfillService.BackfillOptions.class);
        verify(supplierIdentityBackfillService).backfill(captor.capture());
        assertThat(captor.getValue().isDryRun()).isFalse();
        assertThat(captor.getValue().getBatchSize()).isEqualTo(50);
        assertThat(captor.getValue().getSourceSystem()).isEqualTo("custom-source");
    }

    @Test
    void backfillSupplierIdentity_shouldRejectInvalidBatchSize() throws Exception {
        mockMvc.perform(post("/api/v1/admin/supplier-identity/backfill")
                .param("batchSize", "0"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));

        verifyNoInteractions(supplierIdentityBackfillService);
    }
}
