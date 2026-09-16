package com.rsdp.controller;

import com.rsdp.dto.response.PublicCollectionDetailResponse;
import com.rsdp.dto.response.PublicCollectionSummaryResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PublicCollectionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PublicCollectionController} 单元测试。
 */
@WebMvcTest(PublicCollectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PublicCollectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCollectionService publicCollectionService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void listPublished_shouldReturnCollections() throws Exception {
        PublicCollectionSummaryResponse summary = new PublicCollectionSummaryResponse();
        summary.setCollectionId("COL-1");
        summary.setName("中古风客厅");
        summary.setItemCount(3);
        when(publicCollectionService.listPublished()).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/public/collections"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].collectionId").value("COL-1"))
            .andExpect(jsonPath("$.data[0].itemCount").value(3));
    }

    @Test
    void detail_published_shouldReturnDetail() throws Exception {
        PublicCollectionDetailResponse detail = new PublicCollectionDetailResponse();
        detail.setCollectionId("COL-1");
        detail.setName("中古风客厅");
        detail.setItems(List.of());
        detail.setItemCount(0);
        when(publicCollectionService.getPublishedDetail("COL-1")).thenReturn(detail);

        mockMvc.perform(get("/api/v1/public/collections/COL-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.collectionId").value("COL-1"));
    }

    @Test
    void detail_unpublished_shouldReturn404() throws Exception {
        when(publicCollectionService.getPublishedDetail("COL-2"))
            .thenThrow(new ResourceNotFoundException("产品集不存在或未发布"));

        mockMvc.perform(get("/api/v1/public/collections/COL-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(404))
            .andExpect(jsonPath("$.message").value("产品集不存在或未发布"));
    }
}
