package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
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

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private RetrievalService retrievalService;

    @BeforeEach
    void setUp() {
        java.lang.reflect.Field field;
        try {
            field = RetrievalService.class.getDeclaredField("objectMapper");
            field.setAccessible(true);
            field.set(retrievalService, objectMapper);
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

        RspuMaster rspu1 = new RspuMaster();
        rspu1.setRspuId("RSPU-001");
        rspu1.setCategoryCode("FS");
        rspu1.setPositioningLabel("MC");
        rspu1.setColorPrimaryName("焦糖棕");
        rspu1.setMaterialTags("[\"实木\"]");

        RspuMaster rspu2 = new RspuMaster();
        rspu2.setRspuId("RSPU-002");
        rspu2.setCategoryCode("FS");
        rspu2.setPositioningLabel("CR");
        when(rspuMapper.selectBatchIds(any())).thenReturn(List.of(rspu1, rspu2));

        // When
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);

        // Then
        assertThat(results).hasSize(2);
        assertThat(results.get(0).getRspuId()).isEqualTo("RSPU-001");
        assertThat(results.get(0).getFinalScore()).isGreaterThan(results.get(1).getFinalScore());
        assertThat(results.get(0).getMainImageUrl()).isEqualTo("http://localhost/images/IMG-001.jpg");
        assertThat(results.get(0).getMatchReasons()).isNotEmpty();
    }

    @Test
    void searchSimilar_byImage_shouldUseImageEmbedding() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();
        request.setImage(new MockMultipartFile("image", "test.jpg", "image/jpeg", "fake".getBytes()));

        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.3f, 0.4f});
        when(chromaDbClient.query(any(), anyInt(), any())).thenReturn(new ChromaDbClient.QueryResult(Map.of(
            "ids", List.of(List.of()),
            "distances", List.of(List.of()),
            "metadatas", List.of(List.of())
        )));

        // When
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);

        // Then
        assertThat(results).isEmpty();
        verify(embeddingService).embedImage(any());
    }

    @Test
    void searchSimilar_noQuery_shouldThrowException() {
        // Given
        SimilarProductRequest request = new SimilarProductRequest();

        // Then
        assertThrows(IllegalArgumentException.class, () -> retrievalService.searchSimilar(request));
    }
}
