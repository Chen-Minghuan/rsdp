package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import com.rsdp.service.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncTaskProcessor} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskProcessorTest {

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AiRecognitionMapper aiRecognitionMapper;

    @Mock
    private RspuStyleMapper rspuStyleMapper;

    @Mock
    private RspuSceneMapper rspuSceneMapper;

    @Mock
    private VisionService visionService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChromaDbClient chromaDbClient;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private DictResolverService dictResolverService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AsyncTaskProcessor asyncTaskProcessor;

    private String rspuId;
    private String taskId;
    private String imageId;
    private String objectKey;

    @BeforeEach
    void setUp() throws Exception {
        rspuId = "RSPU-TEST01";
        taskId = "TASK-TEST01";
        imageId = "IMG-TEST01";
        objectKey = "images/IMG-TEST01.jpg";

        java.lang.reflect.Field field = AsyncTaskProcessor.class.getDeclaredField("aiModel");
        field.setAccessible(true);
        field.set(asyncTaskProcessor, "qwen3-vl-plus");
        field = AsyncTaskProcessor.class.getDeclaredField("objectMapper");
        field.setAccessible(true);
        field.set(asyncTaskProcessor, objectMapper);
    }

    @Test
    void processProductEntry_shouldPersistStyleAndSceneAssociations() throws Exception {
        // Given
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("processing");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new com.rsdp.entity.AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setSceneTags(List.of("客厅", "书房"));
        labels.setMaterialTags(List.of("实木", "布艺"));
        labels.setColorPrimaryName("焦糖棕");
        labels.setConfidence("high");
        when(visionService.recognizeImage(any())).thenReturn(labels);
        when(embeddingService.embedImage(any())).thenReturn(new float[]{0.1f, 0.2f, 0.3f});

        when(dictResolverService.resolveCodeByName("style", "中古风")).thenReturn("MC");
        when(dictResolverService.resolveCodesByNames("scene", List.of("客厅", "书房"))).thenReturn(List.of("LIVING", "STUDY"));
        when(dictResolverService.resolveCodesByNames("material", List.of("实木", "布艺"))).thenReturn(List.of("WO", "LI"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<RspuMaster> rspuCaptor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).updateById(rspuCaptor.capture());
        assertThat(rspuCaptor.getValue().getPositioningLabel()).isEqualTo("MC");
        assertThat(rspuCaptor.getValue().getStatus()).isEqualTo("active");
        assertThat(rspuCaptor.getValue().getMaterialTags()).contains("WO", "LI");
        assertThat(rspuCaptor.getValue().getStyleVector()).contains("0.1", "0.2", "0.3");

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<float[]>> embeddingsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Map<String, Object>>> metadataCaptor = ArgumentCaptor.forClass(List.class);
        verify(chromaDbClient).upsert(idsCaptor.capture(), embeddingsCaptor.capture(), metadataCaptor.capture(), any());
        assertThat(idsCaptor.getValue()).containsExactly(imageId);
        assertThat(embeddingsCaptor.getValue()).hasSize(1);
        assertThat(embeddingsCaptor.getValue().get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(metadataCaptor.getValue().get(0)).containsEntry("rspu_id", rspuId);

        ArgumentCaptor<RspuStyle> styleCaptor = ArgumentCaptor.forClass(RspuStyle.class);
        verify(rspuStyleMapper, times(1)).insert(styleCaptor.capture());
        assertThat(styleCaptor.getValue().getRspuId()).isEqualTo(rspuId);
        assertThat(styleCaptor.getValue().getStyleCode()).isEqualTo("MC");
        assertThat(styleCaptor.getValue().getIsPrimary()).isTrue();

        ArgumentCaptor<RspuScene> sceneCaptor = ArgumentCaptor.forClass(RspuScene.class);
        verify(rspuSceneMapper, times(2)).insert(sceneCaptor.capture());
        List<RspuScene> scenes = sceneCaptor.getAllValues();
        assertThat(scenes).extracting(RspuScene::getSceneCode).containsExactly("LIVING", "STUDY");
    }
}
