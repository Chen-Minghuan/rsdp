package com.rsdp.controller;

import com.rsdp.entity.ExcelImportRow;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.ExcelAiImportService;
import com.rsdp.service.ExcelImportRowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ExcelAiImportController} 单元测试。
 */
@WebMvcTest(ExcelAiImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class ExcelAiImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ExcelAiImportService excelAiImportService;

    @MockBean
    private ExcelImportRowService excelImportRowService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listRows_shouldReturnRows() throws Exception {
        ExcelImportRow row = new ExcelImportRow();
        row.setRowId(1L);
        row.setBatchId("BATCH-001");
        row.setStatus("success");
        row.setGeneratedRspuId("RSPU-001");

        when(excelImportRowService.listByBatch("BATCH-001")).thenReturn(List.of(row));

        mockMvc.perform(get("/api/v1/products/excel-ai-import/BATCH-001/rows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].status").value("success"));

        verify(excelImportRowService, times(1)).listByBatch("BATCH-001");
    }
}
