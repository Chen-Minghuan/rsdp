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

    // ---------- V19 Scene Revision 端点 ----------

    @Test
    void getAnalysis_withAnalysisRevision_shouldPassThrough() throws Exception {
        when(floorPlanService.getAnalysis("FPA-1", 3))
            .thenReturn(new com.rsdp.dto.response.FloorPlanAnalysisResponse());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/v1/floor-plan/FPA-1").param("analysisRevision", "3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void confirmFurniture_shouldRouteToRevisionEdit() throws Exception {
        com.rsdp.dto.response.FloorPlanFurnitureEditResponse response =
            new com.rsdp.dto.response.FloorPlanFurnitureEditResponse();
        response.setAnalysisRevision(1);
        response.setFurnitureRevision(2);
        when(floorPlanService.editRevisionFurniture(org.mockito.ArgumentMatchers.eq("FPA-1"),
            org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/floor-plan/FPA-1/furniture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"analysisRevision\":1,\"baseFurnitureRevision\":1,"
                    + "\"furniture\":[{\"category\":\"SOFA\"}]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.analysisRevision").value(1))
            .andExpect(jsonPath("$.data.furnitureRevision").value(2));
    }

    @Test
    void confirmFurniture_missingVersions_shouldReject400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/floor-plan/FPA-1/furniture")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"furniture\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void activateRevision_shouldReturnActivatedDetail() throws Exception {
        when(floorPlanService.activateRevision(org.mockito.ArgumentMatchers.eq("FPA-1"),
            org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.rsdp.dto.response.FloorPlanAnalysisResponse());

        mockMvc.perform(post("/api/v1/floor-plan/FPA-1/revisions/2/activate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseActiveAnalysisRevision\":1,\"expectedRoomRevision\":2,"
                    + "\"expectedFurnitureRevision\":1}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void createWorkingRevision_shouldReturnNewRevision() throws Exception {
        when(floorPlanService.createWorkingRevision("FPA-1"))
            .thenReturn(java.util.Map.of("analysisId", "FPA-1", "analysisRevision", 2,
                "revisionStatus", "EDITING"));

        mockMvc.perform(post("/api/v1/floor-plan/FPA-1/working-revision"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.analysisRevision").value(2));
    }

    @Test
    void switchTargetScene_shouldReturn202WithTask() throws Exception {
        when(floorPlanService.switchTargetScene(org.mockito.ArgumentMatchers.eq("FPA-1"),
            org.mockito.ArgumentMatchers.any()))
            .thenReturn(java.util.Map.of("analysisId", "FPA-1", "analysisRevision", 2,
                "taskId", "TASK-1", "status", "PROCESSING"));

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/v1/floor-plan/FPA-1/target-scene")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseAnalysisRevision\":1,\"targetId\":\"SCENE-0002\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.analysisRevision").value(2))
            .andExpect(jsonPath("$.data.status").value("PROCESSING"));
    }
}
