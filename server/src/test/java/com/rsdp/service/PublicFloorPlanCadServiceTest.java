package com.rsdp.service;

import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.PublicCadAnalyzeResponse;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.PublicFloorPlanTokenService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link PublicFloorPlanCadService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PublicFloorPlanCadServiceTest {

    @Mock
    private FloorPlanService floorPlanService;

    @Mock
    private PublicFloorPlanTokenService tokenService;

    @InjectMocks
    private PublicFloorPlanCadService service;

    @Test
    void analyze_shouldCreateTaskAndIssueToken() {
        MockMultipartFile cad = new MockMultipartFile(
            "cad", "plan.dxf", "application/dxf", "cad".getBytes());
        when(floorPlanService.analyzePublicCad(null, cad, "游客户型"))
            .thenReturn(Map.of("analysisId", "FPA-PUBLIC-1", "taskId", "TASK-1"));
        when(tokenService.generate("FPA-PUBLIC-1")).thenReturn("token-1");

        PublicCadAnalyzeResponse response = service.analyze(null, cad, "游客户型");

        assertThat(response.analysisId()).isEqualTo("FPA-PUBLIC-1");
        assertThat(response.taskId()).isEqualTo("TASK-1");
        assertThat(response.accessToken()).isEqualTo("token-1");
    }

    @Test
    void getAnalysis_shouldAppendTokenToProtectedImageUrls() {
        when(tokenService.resolveAnalysisId("token value")).thenReturn("FPA-PUBLIC-1");
        FloorPlanAnalysisResponse detail = new FloorPlanAnalysisResponse();
        detail.setAnalysisId("FPA-PUBLIC-1");
        detail.setImageUrl("/api/v1/images/IMG-1");
        detail.setPreviewUrl("/api/v1/images/IMG-2");
        when(floorPlanService.getPublicAnalysis("FPA-PUBLIC-1")).thenReturn(detail);

        FloorPlanAnalysisResponse response = service.getAnalysis("FPA-PUBLIC-1", "token value");

        assertThat(response.getImageUrl()).endsWith("floorPlanToken=token+value");
        assertThat(response.getPreviewUrl()).endsWith("floorPlanToken=token+value");
    }

    @Test
    void getAnalysis_shouldRejectTokenForAnotherAnalysis() {
        when(tokenService.resolveAnalysisId("token-2")).thenReturn("FPA-PUBLIC-2");

        assertThatThrownBy(() -> service.getAnalysis("FPA-PUBLIC-1", "token-2"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("访问凭证已失效");
    }
}
