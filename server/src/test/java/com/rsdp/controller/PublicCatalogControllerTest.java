package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.dto.response.PublicCategoryResponse;
import com.rsdp.dto.response.PublicProductItemResponse;
import com.rsdp.dto.response.PublicSceneResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.PublicCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PublicCatalogController} 单元测试。
 */
@WebMvcTest(PublicCatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class PublicCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PublicCatalogService publicCatalogService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void products_shouldReturnPagedItems() throws Exception {
        PublicProductItemResponse item = new PublicProductItemResponse();
        item.setRspuId("RSPU-1");
        item.setRspuCode("SF-WJ-002-L");
        item.setProductName("云朵三人位布艺沙发");
        item.setRetailPrice(new BigDecimal("4680.00"));
        item.setPrimaryImageUrl("/api/v1/images/IMG-1");
        item.setVariantCount(3);

        when(publicCatalogService.listProducts(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(PageResult.of(1, 1, 12, List.of(item)));

        mockMvc.perform(get("/api/v1/public/products"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.rows[0].rspuCode").value("SF-WJ-002-L"))
            .andExpect(jsonPath("$.data.rows[0].primaryImageUrl").value("/api/v1/images/IMG-1"));
    }

    @Test
    void products_withFilters_shouldPassParamsToService() throws Exception {
        when(publicCatalogService.listProducts(anyInt(), anyInt(), any(), any(), any(), any(), any(), any(), any()))
            .thenReturn(PageResult.of(0, 1, 12, List.of()));

        mockMvc.perform(get("/api/v1/public/products")
                .param("category", "SF")
                .param("seatCount", "3")
                .param("color", "米白")
                .param("material", "布艺")
                .param("priceMin", "1000")
                .param("priceMax", "5000")
                .param("sort", "price_asc"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));

        org.mockito.Mockito.verify(publicCatalogService).listProducts(
            org.mockito.ArgumentMatchers.eq(1), org.mockito.ArgumentMatchers.eq(12),
            org.mockito.ArgumentMatchers.eq("SF"), org.mockito.ArgumentMatchers.eq("3"),
            org.mockito.ArgumentMatchers.eq("米白"), org.mockito.ArgumentMatchers.eq("布艺"),
            org.mockito.ArgumentMatchers.eq(new BigDecimal("1000")),
            org.mockito.ArgumentMatchers.eq(new BigDecimal("5000")),
            org.mockito.ArgumentMatchers.eq("price_asc"));
    }

    @Test
    void scenes_shouldReturnSceneList() throws Exception {
        PublicSceneResponse scene = new PublicSceneResponse();
        scene.setSceneCode("LIVING");
        scene.setSceneName("客厅");
        scene.setImageUrl("/api/v1/images/IMG-9");

        when(publicCatalogService.listScenes()).thenReturn(List.of(scene));

        mockMvc.perform(get("/api/v1/public/scenes"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].sceneCode").value("LIVING"))
            .andExpect(jsonPath("$.data[0].imageUrl").value("/api/v1/images/IMG-9"));
    }

    @Test
    void categories_shouldReturnCategoryTree() throws Exception {
        PublicCategoryResponse child = new PublicCategoryResponse();
        child.setDictCode("SF-3P");
        child.setDictName("三人位沙发");
        child.setChildren(List.of());
        PublicCategoryResponse root = new PublicCategoryResponse();
        root.setDictCode("SF");
        root.setDictName("沙发");
        root.setChildren(List.of(child));

        when(publicCatalogService.listCategories()).thenReturn(List.of(root));

        mockMvc.perform(get("/api/v1/public/categories"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data[0].dictCode").value("SF"))
            .andExpect(jsonPath("$.data[0].children[0].dictCode").value("SF-3P"));
    }
}
