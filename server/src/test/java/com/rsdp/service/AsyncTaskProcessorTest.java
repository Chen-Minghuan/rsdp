package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.RspuMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncTaskProcessor} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskProcessorTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private VisionService visionService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private ChromaDbClient chromaDbClient;

    @Mock
    private StorageService storageService;

    @Mock
    private AiRecognitionPersistenceService persistenceService;

    @Mock
    private StyleMatchingService styleMatchingService;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantService rspuVariantService;

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

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode("FS");
        when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
    }

    @Test
    void processProductEntry_shouldCallPersistenceServiceAndChromaDb() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setSceneTags(List.of("客厅", "书房"));
        labels.setMaterialTags(List.of("实木", "布艺"));
        labels.setColorPrimaryName("焦糖棕");
        labels.setConfidence("high");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embedImage(any())).thenReturn(embedding);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setCategoryCode("FS");
        rspu.setPositioningLabel("MC");
        rspu.setMaterialTags("[\"WO\",\"LI\"]");
        rspu.setSceneTags("[\"LIVING\",\"STUDY\"]");
        rspu.setStatus("active");
        when(persistenceService.getRspu(rspuId)).thenReturn(rspu);

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        ArgumentCaptor<float[]> embeddingCaptor = ArgumentCaptor.forClass(float[].class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), embeddingCaptor.capture());
        assertThat(labelsCaptor.getValue().getStyle()).isEqualTo("中古风");
        assertThat(embeddingCaptor.getValue()).containsExactly(0.1f, 0.2f, 0.3f);
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));

        ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<float[]>> embeddingsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Map<String, Object>>> metadataCaptor = ArgumentCaptor.forClass(List.class);
        verify(chromaDbClient).upsert(idsCaptor.capture(), embeddingsCaptor.capture(), metadataCaptor.capture(), any());
        assertThat(idsCaptor.getValue()).containsExactly(imageId);
        assertThat(embeddingsCaptor.getValue()).hasSize(1);
        assertThat(embeddingsCaptor.getValue().get(0)).containsExactly(0.1f, 0.2f, 0.3f);
        assertThat(metadataCaptor.getValue().get(0)).containsEntry("rspu_id", rspuId);
        Map<String, Object> metadata = metadataCaptor.getValue().get(0);
        assertThat(metadata).containsEntry("category_code", "FS");
        assertThat(metadata).containsEntry("positioning_label", "MC");
        assertThat(metadata).containsEntry("material_tags", "[\"WO\",\"LI\"]");
        assertThat(metadata).containsEntry("scene_tags", "[\"LIVING\",\"STUDY\"]");
        assertThat(metadata).containsEntry("status", "active");
        assertThat(metadata).containsEntry("image_size", 10L);
        assertThat(metadata).containsKey("color_primary_name");

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(3)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("done");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).isNull();
    }

    @Test
    void processProductEntry_shouldMarkPartialSuccessWhenChromaDbFails() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embedImage(any())).thenReturn(embedding);

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId(rspuId);
        rspu.setStatus("active");
        when(persistenceService.getRspu(rspuId)).thenReturn(rspu);

        doThrow(new RuntimeException("ChromaDB 写入失败")).when(chromaDbClient).upsert(any(), any(), any(), any());

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));

        // Then
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(3)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("partial_success");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).contains("向量写入 ChromaDB 失败");
        assertThat(finalTask.getCompletedAt()).isNotNull();
    }

    @Test
    void processProductEntry_shouldMarkPartialSuccessWhenEmbeddingFails() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        when(embeddingService.embedImage(any())).thenThrow(new RuntimeException("Embedding 服务异常"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));
        verify(chromaDbClient, times(0)).upsert(any(), any(), any(), any());
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(3)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("partial_success");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).contains("生成图片向量失败");
        assertThat(finalTask.getCompletedAt()).isNotNull();
    }

    @Test
    void processProductEntry_shouldCallFailureWhenVisionFails() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);
        when(visionService.recognizeImage(any(), eq("FS"))).thenThrow(new RuntimeException("AI 服务异常"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(persistenceService).saveFailure(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), eq("AI 服务异常"));
        verify(chromaDbClient, times(0)).upsert(any(), any(), any(), any());
    }

    @Test
    void processProductEntry_shouldCallFailureWhenStorageFails() throws Exception {
        // Given
        when(storageService.get(objectKey)).thenThrow(new RuntimeException("存储读取失败"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(persistenceService).saveFailure(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), eq("存储读取失败"));
        verify(visionService, times(0)).recognizeImage(any(), any());
    }
}
