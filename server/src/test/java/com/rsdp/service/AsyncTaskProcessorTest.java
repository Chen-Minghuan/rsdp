package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.OcrResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.service.EmbeddingService.ImageEmbedding;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorHit;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
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
    private ProductVectorStore productVectorStore;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private VectorRebuildService vectorRebuildService;

    @Mock
    private StorageService storageService;

    @Mock
    private AiRecognitionPersistenceService persistenceService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private StyleMatchingService styleMatchingService;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private RspuVariantService rspuVariantService;

    @Mock
    private ProductSubjectCropService subjectCropService;

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
        lenient().when(rspuMapper.selectById(rspuId)).thenReturn(rspu);
        // 默认任务可被认领（pending → processing）；认领失败场景在单个用例中覆盖为 0
        lenient().when(asyncTaskMapper.claimPendingTask(anyString())).thenReturn(1);
        // 默认图片内容版本 1（个别用例覆盖为其他版本以验证向量写入携带的版本）
        ImageAssets defaultImageAsset = new ImageAssets();
        defaultImageAsset.setImageId(imageId);
        defaultImageAsset.setContentRevision(1L);
        lenient().when(imageAssetsMapper.selectById(imageId)).thenReturn(defaultImageAsset);
    }

    @Test
    void processProductEntry_shouldSkipWhenTaskAlreadyClaimed() {
        // Given：任务已被其他执行器认领或不处于 pending 状态
        when(asyncTaskMapper.claimPendingTask(taskId)).thenReturn(0);

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：不再读取图片、不调用 AI、不更新任务状态
        verify(asyncTaskMapper, times(0)).updateById(any(AsyncTask.class));
        verify(persistenceService, times(0)).saveSuccess(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
        verify(persistenceService, times(0)).saveFailure(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), any());
        verify(visionService, times(0)).recognizeImage(any(), any());
    }

    @Test
    void processProductEntry_shouldCallPersistenceServiceAndVectorStore() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        // 图片内容版本 3：向量写入必须携带编码时读取到的该版本（防旧写保护）
        ImageAssets imageAsset = new ImageAssets();
        imageAsset.setImageId(imageId);
        imageAsset.setContentRevision(3L);
        when(imageAssetsMapper.selectById(imageId)).thenReturn(imageAsset);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setSceneTags(List.of("客厅", "书房"));
        labels.setMaterialTags(List.of("实木", "布艺"));
        labels.setColorPrimaryName("焦糖棕");
        labels.setConfidence("high");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(embedding, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(labelsCaptor.getValue().getStyle()).isEqualTo("中古风");
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));

        // 向量写入向量存储：携带编码时读取到的内容版本与输入哈希
        verify(productVectorStore).upsert(eq(imageId), eq(3L), eq("hash-abc"), eq(embedding));

        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(2)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("done");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).isNull();
    }

    @Test
    void processProductEntry_shouldSkipSubjectCropForExcelImportTask() throws Exception {
        // Given：Excel 导入任务（inputData.source=excel_import，图片为直接提取的成品图）
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setInputData("{\"rspuId\":\"" + rspuId + "\",\"imageId\":\"" + imageId
            + "\",\"objectKey\":\"" + objectKey + "\",\"source\":\"excel_import\"}");
        when(asyncTaskMapper.selectById(taskId)).thenReturn(task);

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f, 0.2f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：不做 AI 主体裁剪，直接使用原图走单图识别
        verify(subjectCropService, times(0)).cropAndReplacePrimary(any(), any(), any(), any(), any());
        verify(visionService).recognizeImage(any(), eq("FS"));
        verify(visionService, times(0)).recognizeImage(any(), any(byte[].class), any());
    }

    @Test
    void processProductEntry_shouldMarkPartialSuccessWhenVectorStoreFails() throws Exception {
        // Given
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(embedding, "hash-abc"));

        doThrow(new RuntimeException("向量存储写入失败")).when(productVectorStore)
            .upsert(anyString(), anyLong(), anyString(), any());

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));

        // Then
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(2)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("partial_success");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).contains("向量写入向量存储失败");
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

        when(embeddingService.embedImageWithHash(any())).thenThrow(new RuntimeException("Embedding 服务异常"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(rspuVariantService).initializeDefaultVariant(eq(rspuId), any(AiLabels.class));
        verify(productVectorStore, times(0)).upsert(anyString(), anyLong(), anyString(), any());
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, times(2)).updateById(taskCaptor.capture());
        AsyncTask finalTask = taskCaptor.getValue();
        assertThat(finalTask.getStatus()).isEqualTo("partial_success");
        assertThat(finalTask.getProgress()).isEqualTo(100);
        assertThat(finalTask.getErrorMessage()).contains("生成图片向量失败");
        assertThat(finalTask.getCompletedAt()).isNotNull();
    }

    @Test
    void processProductEntry_shouldPreferOcrMaterialOverVision() throws Exception {
        // Given：视觉直判给出材质，但图中文字有明确材质说明 → 文字优先，覆盖视觉结果
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setMaterialTags(List.of("布艺"));
        OcrResult ocr = new OcrResult();
        ocr.setMaterialDescription("头层牛皮+金属框架");
        labels.setOcr(ocr);
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(labelsCaptor.getValue().getMaterialTags())
            .containsExactly("头层牛皮", "金属框架");
    }

    @Test
    void processProductEntry_shouldKeepVisionMaterialWhenNoOcrText() throws Exception {
        // Given：图中无材质文字说明 → 保留视觉直判结果
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        labels.setMaterialTags(List.of("布艺"));
        labels.setOcr(new OcrResult());
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(labelsCaptor.getValue().getMaterialTags()).containsExactly("布艺");
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
            anyString(), eq("qwen3-vl-plus"), eq("AI 服务异常"), any());
        verify(productVectorStore, times(0)).upsert(anyString(), anyLong(), anyString(), any());
    }

    @Test
    void processProductEntry_shouldCallFailureWhenStorageFails() throws Exception {
        // Given
        when(storageService.get(objectKey)).thenThrow(new RuntimeException("存储读取失败"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(persistenceService).saveFailure(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), eq("存储读取失败"), any());
        verify(visionService, times(0)).recognizeImage(any(), any());
    }

    @Test
    void processProductEntry_shouldFlagDuplicateSuspectWhenSimilarVectorFound() throws Exception {
        // Given：库内存在向量相似度超阈值的其他产品
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());
        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        AiLabels labels = new AiLabels();
        labels.setStyle("侘寂");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f, 0.2f}, "hash-abc"));

        RspuMaster current = new RspuMaster();
        current.setRspuId(rspuId);
        current.setCategoryCode("FS");
        current.setReviewStatus("待复核");
        RspuMaster dup = new RspuMaster();
        dup.setRspuId("RSPU-DUP01");
        dup.setRspuCode("FS-WJ-001-M");
        dup.setProductName("扶摇沙发");
        when(rspuMapper.selectById(rspuId)).thenReturn(current);
        when(rspuMapper.selectById("RSPU-DUP01")).thenReturn(dup);

        java.lang.reflect.Field thresholdField = AsyncTaskProcessor.class.getDeclaredField("duplicateSimilarThreshold");
        thresholdField.setAccessible(true);
        thresholdField.set(asyncTaskProcessor, 0.95);

        // 距离 0.06 → 相似度 0.97 ≥ 0.95
        when(productVectorStore.search(any(), org.mockito.ArgumentMatchers.anyInt(), any(), eq(false)))
            .thenReturn(List.of(new VectorHit("IMG-OTHER", "RSPU-DUP01", 0.06)));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：标记"存疑-疑似同款"，注明命中产品与相似度
        ArgumentCaptor<RspuMaster> captor = ArgumentCaptor.forClass(RspuMaster.class);
        verify(rspuMapper).updateById(captor.capture());
        assertThat(captor.getValue().getReviewStatus()).isEqualTo("存疑");
        assertThat(captor.getValue().getReviewComment()).contains("疑似").contains("FS-WJ-001-M").contains("97%");
    }

    @Test
    void processProductEntry_shouldNotFlagDuplicateWhenBelowThreshold() throws Exception {
        // Given：库内最相似产品相似度低于阈值（距离 0.5 → 相似度 0.75 < 0.95）
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());
        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        AiLabels labels = new AiLabels();
        labels.setStyle("侘寂");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        RspuMaster current = new RspuMaster();
        current.setRspuId(rspuId);
        current.setCategoryCode("FS");
        current.setReviewStatus("待复核");
        when(rspuMapper.selectById(rspuId)).thenReturn(current);

        java.lang.reflect.Field thresholdField = AsyncTaskProcessor.class.getDeclaredField("duplicateSimilarThreshold");
        thresholdField.setAccessible(true);
        thresholdField.set(asyncTaskProcessor, 0.95);

        when(productVectorStore.search(any(), org.mockito.ArgumentMatchers.anyInt(), any(), eq(false)))
            .thenReturn(List.of(new VectorHit("IMG-OTHER", "RSPU-OTHER", 0.5)));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：不更新复核状态
        verify(rspuMapper, org.mockito.Mockito.never()).updateById(any(RspuMaster.class));
    }

    @Test
    void processProductEntry_shouldMergePageOcrIntoLabels() throws Exception {
        // Given：任务 input_data 含页面级 OCR 文字（文档导入场景）
        AsyncTask task = new AsyncTask();
        task.setInputData("{\"rspuId\":\"RSPU-TEST01\",\"pageOcr\":{"
            + "\"productName\":\"页面品名\","
            + "\"modelNumber\":\"LK-2450\","
            + "\"dimensionText\":\"2450*900*850mm\","
            + "\"rawText\":\"页面原始文字\"}}");
        when(asyncTaskMapper.selectById(anyString())).thenReturn(task);

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        // 页面级品名来自真实说明文字，优先于图像 OCR 的猜测性品名（覆盖）；
        // 型号/尺寸图像 OCR 缺失，由页面文字补缺
        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        OcrResult cropOcr = new OcrResult();
        cropOcr.setProductName("裁剪图品名");
        cropOcr.setRawText("裁剪图文字");
        labels.setOcr(cropOcr);
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：合并后的 OCR 结果随 labels 持久化
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        OcrResult merged = labelsCaptor.getValue().getOcr();
        assertThat(merged.getProductName()).isEqualTo("页面品名");
        assertThat(merged.getModelNumber()).isEqualTo("LK-2450");
        assertThat(merged.getDimensionText()).isEqualTo("2450*900*850mm");
        assertThat(merged.getRawText()).isEqualTo("页面原始文字\n裁剪图文字");
    }

    @Test
    void processProductEntry_shouldUsePageOcrWhenCropOcrMissing() throws Exception {
        // Given：裁剪图 OCR 整体缺失时，直接采用页面级 OCR
        AsyncTask task = new AsyncTask();
        task.setInputData("{\"pageOcr\":{\"productName\":\"页面品名\",\"rawText\":\"页面原始文字\"}}");
        when(asyncTaskMapper.selectById(anyString())).thenReturn(task);

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());
        when(storageService.get(objectKey)).thenReturn(imageStream);

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);

        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        ArgumentCaptor<AiLabels> labelsCaptor = ArgumentCaptor.forClass(AiLabels.class);
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), labelsCaptor.capture(),
            org.mockito.ArgumentMatchers.anyInt(), any());
        OcrResult merged = labelsCaptor.getValue().getOcr();
        assertThat(merged.getProductName()).isEqualTo("页面品名");
        assertThat(merged.getRawText()).isEqualTo("页面原始文字");
    }

    @Test
    void processProductEntry_shouldPassTaskCreatorAsAuditOperator() throws Exception {
        // Given：P0-1 异步线程无 SecurityContext，审计操作人显式取任务 createdBy
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setCreatedBy("editor01");
        when(asyncTaskMapper.selectById(anyString())).thenReturn(task);

        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：saveSuccess 第 8 个参数（operator）= 任务创建人，而非 anonymous
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), any(AiLabels.class),
            org.mockito.ArgumentMatchers.anyInt(), eq("editor01"));
    }

    @Test
    void processProductEntry_shouldFallbackOperatorToSystemWhenNoCreator() throws Exception {
        // Given：任务无 createdBy 时审计操作人按 system
        when(asyncTaskMapper.selectById(anyString())).thenReturn(new AsyncTask());
        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then
        verify(persistenceService).saveSuccess(eq(taskId), eq(rspuId), eq(imageId),
            anyString(), eq("qwen3-vl-plus"), any(AiLabels.class),
            org.mockito.ArgumentMatchers.anyInt(), eq("system"));
    }

    @Test
    void processProductEntry_shouldAuditCategoryCorrectionWithTaskCreator() throws Exception {
        // Given：P0-2 品类自动判定纠正此前绕过审计；任务创建人 editor01
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setCreatedBy("editor01");
        task.setInputData("{\"categoryAutoDetect\":true}");
        when(asyncTaskMapper.selectById(anyString())).thenReturn(task);

        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));
        when(visionService.classifyCategory(any())).thenReturn("TB");

        AiLabels labels = new AiLabels();
        labels.setStyle("中古风");
        when(visionService.recognizeImage(any(), eq("TB"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：品类纠正有 logUpdate 审计，操作人=任务创建人，旧值 FS → 新值 TB
        ArgumentCaptor<Object> oldCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> newCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditLogService).logUpdate(eq("rspu_master"), eq(rspuId),
            oldCaptor.capture(), newCaptor.capture(), eq("editor01"));
        assertThat(((RspuMaster) oldCaptor.getValue()).getCategoryCode()).isEqualTo("FS");
        assertThat(((RspuMaster) newCaptor.getValue()).getCategoryCode()).isEqualTo("TB");
    }

    @Test
    void processProductEntry_shouldAuditDuplicateSuspectWithTaskCreator() throws Exception {
        // Given：P0-2 同款存疑标记与 markRspuAsDoubtful 口径对齐（logReview），操作人=任务创建人
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setCreatedBy("editor01");
        when(asyncTaskMapper.selectById(anyString())).thenReturn(task);
        when(storageService.get(objectKey)).thenReturn(new ByteArrayInputStream("fake-image".getBytes()));

        AiLabels labels = new AiLabels();
        labels.setStyle("侘寂");
        when(visionService.recognizeImage(any(), eq("FS"))).thenReturn(labels);
        when(embeddingService.embedImageWithHash(any())).thenReturn(new ImageEmbedding(new float[]{0.1f}, "hash-abc"));

        RspuMaster current = new RspuMaster();
        current.setRspuId(rspuId);
        current.setCategoryCode("FS");
        current.setReviewStatus("待复核");
        RspuMaster dup = new RspuMaster();
        dup.setRspuId("RSPU-DUP01");
        dup.setRspuCode("FS-WJ-001-M");
        when(rspuMapper.selectById(rspuId)).thenReturn(current);
        when(rspuMapper.selectById("RSPU-DUP01")).thenReturn(dup);

        java.lang.reflect.Field thresholdField = AsyncTaskProcessor.class.getDeclaredField("duplicateSimilarThreshold");
        thresholdField.setAccessible(true);
        thresholdField.set(asyncTaskProcessor, 0.95);

        when(productVectorStore.search(any(), org.mockito.ArgumentMatchers.anyInt(), any(), eq(false)))
            .thenReturn(List.of(new VectorHit("IMG-OTHER", "RSPU-DUP01", 0.06)));

        // When
        asyncTaskProcessor.processProductEntry(taskId, rspuId, imageId, objectKey);

        // Then：置存疑有 logReview 审计，操作人=任务创建人
        verify(auditLogService).logReview(eq("rspu_master"), eq(rspuId), any(), any(), eq("editor01"));
    }
}
