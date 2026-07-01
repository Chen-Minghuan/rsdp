package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link RetrievalService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class RetrievalServiceTest {

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChromaDbClient chromaDbClient;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private VisionService visionService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        injectField("objectMapper", objectMapper);
        injectField("visionService", visionService);
    }

    private void injectField(String fieldName, Object value) {
        try {
            java.lang.reflect.Field field = RetrievalService.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(retrievalService, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void searchSimilar_byText_shouldReturnAggregatedResults() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();
        request.setText("中古风实木沙发");
        request.setTopK(5);

        when(embeddingService.embedText("中古风实木沙发")).thenReturn(new float[]{0.1f, 0.2f});

        Map<String, Object> response = Map.of(
            "ids", List.of(List.of("IMG-001", "IMG-002", "IMG-003")),
            "distances", List.of(List.of(0.1, 0.2, 0.15)),
            "metadatas", List.of(List.of(
                Map.of("rspu_id", "RSPU-001", "category_code", "FS", "positioning_label", "MC"),
                Map.of("rspu_id", "RSPU-001", "category_code", "FS", "positioning_label", "MC"),
                Map.of("rspu_id", "RSPU-002", "category_code", "FS", "positioning_label", "CR")
            ))
        );
        when(chromaDbClient.query(any(), anyInt(), any())).thenReturn(new ChromaDbClient.QueryResult(response));

        ImageAssets img1 = new ImageAssets();
        img1.setImageId("IMG-001");
        img1.setStorageUrl("http://localhost/images/IMG-001.jpg");

        ImageAssets img3 = new ImageAssets();
        img3.setImageId("IMG-003");
        img3.setStorageUrl("http://localhost/images/IMG-003.jpg");
        when(imageAssetsMapper.selectBatchIds(any())).thenReturn(List.of(img1, img3));

        RspuMaster rspu1 = buildRspu("RSPU-001", "FS", "MC", "焦糖棕", "[\"实木\"]", "[\"客厅\"]", null);
        RspuMaster rspu2 = buildRspu("RSPU-002", "FS", "CR", null, null, null, null);
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu1, rspu2));

        // When
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(results.get(0).getFinalScore()).isGreaterThan(results.get(1).getFinalScore());
        assertThat(results.get(0).getMainImageUrl()).isEqualTo("http://localhost/images/IMG-001.jpg");
        assertThat(results.get(0).getMatchReasons()).isNotEmpty();
        verify(visionService, never()).recognizeImage(any());
    }

    @Test
    void searchSimilar_byImage_shouldExtractLabelsAndApplyBoost() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();
        request.setImage(new MockMultipartFile("image", "test.jpg", "image/jpeg", "fake".getBytes()));
        request.setTopK(5);

        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.3f, 0.4f});

        AiLabels labels = new AiLabels();
        labels.setStyle("MC");
        labels.setColorPrimaryName("焦糖棕");
        labels.setMaterialTags(List.of("实木", "布艺"));
        labels.setSceneTags(List.of("客厅"));
        labels.setSixDimTags(Map.of("A", "直线条", "B", "高靠背"));
        when(visionService.recognizeImage(any())).thenReturn(labels);

        Map<String, Object> response = Map.of(
            "ids", List.of(List.of("IMG-001", "IMG-002", "IMG-003")),
            "distances", List.of(List.of(0.1, 0.2, 0.15)),
            "metadatas", List.of(List.of(
                Map.of("rspu_id", "RSPU-001", "category_code", "FS", "positioning_label", "MC"),
                Map.of("rspu_id", "RSPU-002", "category_code", "FS", "positioning_label", "CR"),
                Map.of("rspu_id", "RSPU-003", "category_code", "FS", "positioning_label", "MC")
            ))
        );
        when(chromaDbClient.query(any(), anyInt(), any())).thenReturn(new ChromaDbClient.QueryResult(response));

        ImageAssets img1 = new ImageAssets();
        img1.setImageId("IMG-001");
        img1.setStorageUrl("http://localhost/images/IMG-001.jpg");
        when(imageAssetsMapper.selectBatchIds(any())).thenReturn(List.of(img1));

        RspuMaster rspu1 = buildRspu("RSPU-001", "FS", "MC", "焦糖棕", "[\"实木\"]", "[\"客厅\"]",
            "{\"A\":\"直线条\",\"B\":\"高靠背\"}");
        rspu1.setAestheticsConfidence("high");

        RspuMaster rspu2 = buildRspu("RSPU-002", "FS", "CR", "米白", "[\"布艺\"]", "[\"卧室\"]",
            "{\"A\":\"曲线条\",\"B\":\"低靠背\"}");
        rspu2.setAestheticsConfidence("mid");

        RspuMaster rspu3 = buildRspu("RSPU-003", "FS", "MC", "焦糖棕", "[\"实木\"]", "[\"客厅\"]",
            "{\"A\":\"直线条\",\"B\":\"高靠背\"}");
        rspu3.setAestheticsConfidence("high");

        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu1, rspu2, rspu3));

        // When
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);

        // Then
        assertThat(results).hasSize(2); // RSPU-001 与 RSPU-003 指纹相同，去重后剩一个
        assertThat(results.get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(results.get(0).getAestheticsConfidence()).isEqualTo("high");
        assertThat(results.get(0).getMatchReasons()).contains("风格一致：MC", "主色一致：焦糖棕");
        assertThat(results.get(0).getFinalScore()).isGreaterThan(results.get(1).getFinalScore());
        verify(visionService).recognizeImage(any());
    }

    @Test
    void searchSimilar_visionServiceFailure_shouldFallbackToVectorOnly() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();
        request.setImage(new MockMultipartFile("image", "test.jpg", "image/jpeg", "fake".getBytes()));
        request.setTopK(5);

        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.3f, 0.4f});
        when(visionService.recognizeImage(any())).thenThrow(new ExternalServiceException("AI 识别失败"));

        Map<String, Object> response = Map.of(
            "ids", List.of(List.of("IMG-001")),
            "distances", List.of(List.of(0.1)),
            "metadatas", List.of(List.of(
                Map.of("rspu_id", "RSPU-001", "category_code", "FS", "positioning_label", "MC")
            ))
        );
        when(chromaDbClient.query(any(), anyInt(), any())).thenReturn(new ChromaDbClient.QueryResult(response));

        ImageAssets img1 = new ImageAssets();
        img1.setImageId("IMG-001");
        img1.setStorageUrl("http://localhost/images/IMG-001.jpg");
        when(imageAssetsMapper.selectBatchIds(any())).thenReturn(List.of(img1));

        RspuMaster rspu1 = buildRspu("RSPU-001", "FS", "MC", "焦糖棕", "[\"实木\"]", "[\"客厅\"]", null);
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu1));

        // When
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);

        // Then
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(results.get(0).getFinalScore()).isGreaterThan(0);
    }

    @Test
    void searchSimilar_noQuery_shouldThrowException() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();

        // Then
        assertThrows(IllegalArgumentException.class, () -> retrievalService.searchSimilar(request));
    }

    private RspuMaster buildRspu(String rspuId, String categoryCode, String positioningLabel,
                                 String colorPrimaryName, String materialTags, String sceneTags,
                                 String sixDimTags) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode(categoryCode);
        rspu.setPositioningLabel(positioningLabel);
        rspu.setColorPrimaryName(colorPrimaryName);
        rspu.setMaterialTags(materialTags);
        rspu.setSceneTags(sceneTags);
        rspu.setSixDimTags(sixDimTags);
        return rspu;
    }
}
