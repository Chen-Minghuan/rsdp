package com.rsdp.controller;

import com.rsdp.dto.request.OfficeCatalogRequest;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.service.ExcelImportRowService;
import com.rsdp.service.OfficeCatalogBatchAccess;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OfficeCatalogBatchControllerTest {
    @Test
    void preservesScopedHistoryWithoutAWriteHandler() throws Exception {
        var rows = mock(ExcelImportRowService.class);
        var access = mock(OfficeCatalogBatchAccess.class);
        var actor = new OfficeCatalogRequest.Actor("WS-1", "OFFICE-1", null, "F001");
        var batch = new ExcelImportBatch();
        batch.setBatchId("BATCH-1");
        batch.setStatus("done");
        when(access.requireHistoryBatch("BATCH-1", actor)).thenReturn(batch);
        when(rows.listByBatch("BATCH-1")).thenReturn(List.of());
        var mvc = MockMvcBuilders.standaloneSetup(new OfficeCatalogBatchController(rows, access)).build();

        mvc.perform(get("/api/v1/products/office/batches/BATCH-1/status")
                .param("workspaceId", "WS-1").param("actorId", "OFFICE-1").param("factoryCode", "F001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.status").value("done"));
        verify(access).requireHistoryBatch("BATCH-1", actor);
        mvc.perform(post("/api/v1/products/office/batches/BATCH-1/confirm"))
            .andExpect(status().isNotFound());
    }
}
