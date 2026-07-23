package com.rsdp.controller;

import com.rsdp.entity.ExcelImportRow;
import com.rsdp.exception.ForbiddenException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

        verify(excelAiImportService, times(1)).getAccessibleBatch("BATCH-001");
        verify(excelImportRowService, times(1)).listByBatch("BATCH-001");
    }

    @Test
    void listRows_shouldReturn403WhenBatchNotOwned() throws Exception {
        // P1-12：无权访问他人批次 → 403，且不查询行数据
        when(excelAiImportService.getAccessibleBatch("BATCH-001"))
            .thenThrow(new ForbiddenException("无权访问该导入批次: BATCH-001"));

        mockMvc.perform(get("/api/v1/products/excel-ai-import/BATCH-001/rows"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(403));

        verify(excelImportRowService, never()).listByBatch(any());
    }

    @Test
    void getStatus_shouldReturn403WhenBatchNotOwned() throws Exception {
        // P1-12：查询批次状态同样先做归属校验
        when(excelAiImportService.getAccessibleBatch("BATCH-001"))
            .thenThrow(new ForbiddenException("无权访问该导入批次: BATCH-001"));

        mockMvc.perform(get("/api/v1/products/excel-ai-import/BATCH-001"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(403));

        verify(excelAiImportService, never()).getStatus(any());
    }

    @Test
    void preview_shouldPassSheetIndexToService() throws Exception {
        // V18：preview 支持可选 sheetIndex 表单参数（多 Sheet 逐一导入），默认 0
        when(excelAiImportService.previewMapping(any(), eq(2)))
            .thenReturn(new com.rsdp.dto.response.ExcelAiMappingResponse());

        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
            "file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/products/excel-ai-import/preview")
                .file(file)
                .param("sheetIndex", "2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(excelAiImportService, times(1)).previewMapping(any(), eq(2));
    }

    @Test
    void preview_shouldDefaultSheetIndexToZero() throws Exception {
        when(excelAiImportService.previewMapping(any(), eq(0)))
            .thenReturn(new com.rsdp.dto.response.ExcelAiMappingResponse());

        org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
            "file", "test.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "x".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .multipart("/api/v1/products/excel-ai-import/preview")
                .file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        verify(excelAiImportService, times(1)).previewMapping(any(), eq(0));
    }
}
