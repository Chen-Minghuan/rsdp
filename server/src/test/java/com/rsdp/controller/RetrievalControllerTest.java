package com.rsdp.controller;

import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.service.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rsdp.exception.GlobalExceptionHandler;

/**
 * {@link RetrievalController} 单元测试。
 */
@WebMvcTest(RetrievalController.class)
@Import(GlobalExceptionHandler.class)
class RetrievalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RetrievalService retrievalService;

    @Test
    void searchSimilar_withImage_shouldReturnResults() throws Exception {
        SimilarProductResponse response = new SimilarProductResponse();
        response.setRspuId("RSPU-001");
        response.setCategoryCode("FS");
        response.setPositioningLabel("MC");
        response.setVectorScore(0.92);
        response.setFinalScore(0.95);
        response.setMatchReasons(List.of("向量相似", "类别一致"));

        when(retrievalService.searchSimilar(any(SimilarProductRequest.class))).thenReturn(List.of(response));

        MockMultipartFile image = new MockMultipartFile("image", "test.jpg", "image/jpeg", "fake".getBytes());

        mockMvc.perform(multipart("/api/v1/retrieval/similar")
                .file(image)
                .param("categoryCode", "FS")
                .param("topK", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].rspuId").value("RSPU-001"))
            .andExpect(jsonPath("$.data[0].finalScore").value(0.95));
    }

    @Test
    void searchSimilar_withText_shouldReturnResults() throws Exception {
        when(retrievalService.searchSimilar(any(SimilarProductRequest.class))).thenReturn(List.of());

        mockMvc.perform(multipart("/api/v1/retrieval/similar")
                .param("text", "中古风沙发")
                .param("positioningLabel", "MC"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void searchSimilar_withoutQuery_shouldReturnBadRequest() throws Exception {
        when(retrievalService.searchSimilar(any(SimilarProductRequest.class)))
            .thenThrow(new IllegalArgumentException("请提供查询图片或文本"));

        mockMvc.perform(multipart("/api/v1/retrieval/similar"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("请提供查询图片或文本"));
    }
}
