package com.rsdp.controller;

import com.rsdp.dto.response.DocumentImportResult;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link DocumentImportController} 单元测试。
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
    private com.rsdp.service.SceneImportService sceneImportService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void importFromDocument_shouldReturnResult() throws Exception {
        DocumentImportResult result = new DocumentImportResult();
        result.setBatchId("BATCH-TEST01");
        result.setTotalPages(2);
        result.setProductPages(1);
        result.setTotalProducts(2);
        result.setSuccessCount(2);
        result.setFailedCount(0);
        result.setTaskIds(List.of("TASK-01", "TASK-02"));
        result.setRspuIds(List.of("RSPU-01", "RSPU-02"));

        when(pdfImportService.importPdf(any(), anyString())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/v1/products/document-import")
                .file(file)
                .param("categoryHint", "SF"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-TEST01"))
            .andExpect(jsonPath("$.data.totalProducts").value(2))
            .andExpect(jsonPath("$.data.taskIds").isArray());
    }

    @Test
    void importFromDocument_withoutCategoryHint_shouldReturnResult() throws Exception {
        DocumentImportResult result = new DocumentImportResult();
        result.setBatchId("BATCH-TEST02");
        result.setTotalPages(1);
        result.setSuccessCount(0);

        when(pdfImportService.importPdf(any(), any())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", "%PDF".getBytes());

        mockMvc.perform(multipart("/api/v1/products/document-import")
                .file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-TEST02"));
    }

    @Test
    void importFromScene_shouldReturnResult() throws Exception {
        com.rsdp.dto.response.SceneImportResult result = new com.rsdp.dto.response.SceneImportResult();
        result.setBatchId("BATCH-SCENE01");
        result.setTotalProducts(2);
        result.setSuccessCount(2);
        com.rsdp.dto.response.SceneImportResult.SceneImportProduct product =
            new com.rsdp.dto.response.SceneImportResult.SceneImportProduct();
        product.setCategoryCode("SF");
        product.setStatus("success");
        product.setRspuId("RSPU-01");
        product.setTaskId("TASK-01");
        product.setImageId("IMG-01");
        result.getProducts().add(product);

        when(sceneImportService.importScene(any(), any())).thenReturn(result);

        MockMultipartFile file = new MockMultipartFile("file", "scene.jpg", "image/jpeg", "fake-image".getBytes());

        mockMvc.perform(multipart("/api/v1/products/scene-import")
                .file(file))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.batchId").value("BATCH-SCENE01"))
            .andExpect(jsonPath("$.data.totalProducts").value(2))
            .andExpect(jsonPath("$.data.products[0].rspuId").value("RSPU-01"));
    }
}
