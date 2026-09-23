package com.rsdp.controller;

import com.rsdp.dto.response.FloorPlanBatchDeleteResponse;
import com.rsdp.exception.GlobalExceptionHandler;
import com.rsdp.security.JwtAuthenticationFilter;
import com.rsdp.service.FloorPlanMatchingService;
import com.rsdp.service.FloorPlanService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FloorPlanController} 接口测试。
 */
@WebMvcTest(FloorPlanController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class FloorPlanControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private FloorPlanService floorPlanService;

    @MockBean
    private FloorPlanMatchingService floorPlanMatchingService;

    @MockBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @Test
    void batchDeleteAnalyses_shouldReturnDeleteSummary() throws Exception {
        FloorPlanBatchDeleteResponse response = new FloorPlanBatchDeleteResponse(
            1, 1, List.of(new FloorPlanBatchDeleteResponse.Failure("FPA-2", "记录不存在")));
        when(floorPlanService.batchDeleteAnalyses(List.of("FPA-1", "FPA-2"))).thenReturn(response);

        mockMvc.perform(post("/api/v1/floor-plan/batch-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"analysisIds\":[\"FPA-1\",\"FPA-2\"]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.deletedCount").value(1))
            .andExpect(jsonPath("$.data.failedCount").value(1))
            .andExpect(jsonPath("$.data.failures[0].analysisId").value("FPA-2"));
    }

    @Test
    void batchDeleteAnalyses_emptyIds_shouldRejectRequest() throws Exception {
        mockMvc.perform(post("/api/v1/floor-plan/batch-delete")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"analysisIds\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }
}
