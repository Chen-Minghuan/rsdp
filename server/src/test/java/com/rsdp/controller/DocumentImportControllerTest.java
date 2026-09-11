package com.rsdp.controller;

import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.dto.response.DocumentImportSubmitResult;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PdfImportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DocumentImportController} 单元测试（阶段 3.1：异步批次化）。
 */
@WebMvcTest(DocumentImportController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class DocumentImportControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PdfImportService pdfImportService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void importFromDocument_shouldReturnBatchIdImmediately() throws Exception {
        // 异步化：提交接口只返回 batchId，不含处理结果
        when(pdfImportService.importPdf(any(), anyString()))
            .thenReturn(new DocumentImportSubmitResult("BATCH-TEST01"));

        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/v1/products/document-import")
                .file(file)
                .param("categoryHint", "SF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-TEST01"));
    }

    @Test
    void importFromDocument_withoutCategoryHint_shouldReturnBatchId() throws Exception {
        when(pdfImportService.importPdf(any(), any()))
            .thenReturn(new DocumentImportSubmitResult("BATCH-TEST02"));

        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/v1/products/document-import")
                .file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-TEST02"));
    }

    @Test
    void getImportBatch_shouldReturnBatchProgress() throws Exception {
        DocumentImportResult result = new DocumentImportResult();
        result.setBatchId("BATCH-TEST03");
        result.setStatus("processing");
        result.setTotalPages(10);
        result.setProcessedPages(4);
        result.setProductPages(3);
        result.setTotalProducts(5);
        result.setSuccessCount(4);
        result.setFailedCount(0);
        result.setSkippedCount(1);
        result.setTaskIds(List.of("TASK-01", "TASK-02"));
        result.setRspuIds(List.of("RSPU-01", "RSPU-02"));

        when(pdfImportService.getBatchResult("BATCH-TEST03")).thenReturn(result);

        mockMvc.perform(get("/api/v1/products/document-import/BATCH-TEST03"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-TEST03"))
            .andExpect(jsonPath("$.data.status").value("processing"))
            .andExpect(jsonPath("$.data.processedPages").value(4))
            .andExpect(jsonPath("$.data.totalPages").value(10))
            .andExpect(jsonPath("$.data.skippedCount").value(1))
            .andExpect(jsonPath("$.data.taskIds").isArray());
    }
}
