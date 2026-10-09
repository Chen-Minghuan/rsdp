package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.exception.BusinessException;
import com.rsdp.floorplan.parser.FloorPlanFileType;
import com.rsdp.floorplan.parser.FloorPlanParseRequest;
import com.rsdp.floorplan.parser.FloorPlanParseResult;
import com.rsdp.floorplan.parser.FloorPlanParser;
import com.rsdp.floorplan.parser.FloorPlanParserRegistry;
import com.rsdp.floorplan.parser.dto.CadParseResult;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuDuplicateSuspectMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.service.vector.ProductVectorStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncTaskProcessor#processFloorPlanAnalysis} Scene Revision 写路径测试（V19）：
 * 新解析落 Revision（input_data 携带 analysisRevision）、target-scene 重解析携带 targetId、
 * 失败落 FAILED revision、旧任务（无 analysisRevision）回退 working ?? latest ?? 1。
 */
@ExtendWith(MockitoExtension.class)
class FloorPlanAnalysisProcessorTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;
    @Mock
    private RspuMapper rspuMapper;
    @Mock
    private VisionService visionService;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private ProductVectorStore productVectorStore;
    @Mock
    private ImageAssetsMapper imageAssetsMapper;
    @Mock
    private StorageService storageService;
    @Mock
    private AiRecognitionPersistenceService persistenceService;
    @Mock
    private CategoryShadowService categoryShadowService;
    @Mock
    private AuditLogService auditLogService;
    @Mock
    private StyleMatchingService styleMatchingService;
    @Mock
    private RspuVariantService rspuVariantService;
    @Mock
    private ProductSubjectCropService subjectCropService;
    @Mock
    private FloorPlanAnalysisMapper floorPlanAnalysisMapper;
    @Mock
    private RspuDuplicateSuspectMapper duplicateSuspectMapper;
    @Mock
    private FloorPlanParserRegistry floorPlanParserRegistry;
    @Mock
    private ObjectProvider<FloorPlanService> floorPlanServiceProvider;
    @Mock
    private ObjectProvider<PdfImportService> pdfImportServiceProvider;
    @Mock
    private ObjectProvider<ExcelAiImportService> excelImportServiceProvider;
    @Mock
    private VectorRebuildService vectorRebuildService;
    @Mock
    private FloorPlanService floorPlanService;
    @Mock
    private FloorPlanParser cadParser;
    @Mock
    private FloorPlanParser visionParser;

    @Mock
    private FloorPlanFurnitureSemanticService furnitureSemanticService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    private AsyncTaskProcessor asyncTaskProcessor;

    @BeforeEach
    void setUp() {
        // 手动构造：三个 ObjectProvider 同擦除类型时 @InjectMocks 构造器注入不可靠
        asyncTaskProcessor = new AsyncTaskProcessor(asyncTaskMapper, rspuMapper, visionService,
            embeddingService, productVectorStore, imageAssetsMapper, storageService, persistenceService,
            categoryShadowService, auditLogService, styleMatchingService, rspuVariantService,
            subjectCropService, floorPlanAnalysisMapper, duplicateSuspectMapper, floorPlanParserRegistry,
            furnitureSemanticService, floorPlanServiceProvider, pdfImportServiceProvider, excelImportServiceProvider,
            objectMapper, vectorRebuildService);
        lenient().when(asyncTaskMapper.claimPendingTask(anyString())).thenReturn(1);
        lenient().when(floorPlanServiceProvider.getObject()).thenReturn(floorPlanService);
    }

    /** target-scene 重解析：input_data 的 analysisRevision/targetId 透传到落库与解析请求。 */
    @Test
    void cadTask_shouldFinalizeIntoRevisionAndPassTargetId() throws Exception {
        AsyncTask task = task("TASK-1",
            "{\"analysisId\":\"FPA-1\",\"analysisRevision\":4,\"targetId\":\"SCENE-0002\"}");
        when(asyncTaskMapper.selectById("TASK-1")).thenReturn(task);
        when(storageService.get("cad/fp.dwg"))
            .thenReturn(new ByteArrayInputStream("fake-dwg".getBytes()));
        CadParseResult cadResult = new CadParseResult();
        cadResult.setSuccess(true);
        cadResult.setParseStatus("SUCCESS");
        when(floorPlanParserRegistry.resolve(FloorPlanFileType.CAD)).thenReturn(cadParser);
        when(cadParser.parse(any())).thenReturn(FloorPlanParseResult.cad(cadResult, null));

        asyncTaskProcessor.processFloorPlanAnalysis("TASK-1", "FPA-1", null, null, "cad/fp.dwg", false);

        // targetId 透传解析请求（multipart 附带）
        ArgumentCaptor<FloorPlanParseRequest> requestCaptor =
            ArgumentCaptor.forClass(FloorPlanParseRequest.class);
        verify(cadParser).parse(requestCaptor.capture());
        assertThat(requestCaptor.getValue().targetId()).isEqualTo("SCENE-0002");
        // 落库绑定 input_data 指定的 Revision 4
        verify(floorPlanService).finalizeCadParseSuccess("FPA-1", 4, cadResult, null, false);
    }

    /** 旧任务（input_data 无 analysisRevision）：回退分析 working ?? latest ?? 1。 */
    @Test
    void visionTask_legacyWithoutRevision_shouldFallbackToLatestPointer() throws Exception {
        AsyncTask task = task("TASK-2", "{\"analysisId\":\"FPA-1\"}");
        when(asyncTaskMapper.selectById("TASK-2")).thenReturn(task);
        when(storageService.get("images/fp.jpg"))
            .thenReturn(new ByteArrayInputStream("fake-jpg".getBytes()));
        FloorPlanAnalysis analysis = new FloorPlanAnalysis();
        analysis.setAnalysisId("FPA-1");
        analysis.setWorkingAnalysisRevision(null);
        analysis.setLatestAnalysisRevision(3);
        when(floorPlanAnalysisMapper.selectById("FPA-1")).thenReturn(analysis);
        FloorPlanDetectResult detected = new FloorPlanDetectResult();
        detected.setRooms(List.of());
        when(floorPlanParserRegistry.resolveByExtension("jpg")).thenReturn(visionParser);
        when(visionParser.parse(any())).thenReturn(FloorPlanParseResult.vision(detected));

        asyncTaskProcessor.processFloorPlanAnalysis("TASK-2", "FPA-1", "images/fp.jpg", null);

        verify(floorPlanService).finalizeVisionParseSuccess("FPA-1", 3, detected);
    }

    /** 解析失败：Revision → FAILED + 任务 failed。 */
    @Test
    void cadTask_parseFailure_shouldFinalizeFailureRevision() throws Exception {
        AsyncTask task = task("TASK-3", "{\"analysisId\":\"FPA-1\",\"analysisRevision\":2}");
        when(asyncTaskMapper.selectById("TASK-3")).thenReturn(task);
        when(storageService.get("cad/fp.dwg"))
            .thenReturn(new ByteArrayInputStream("fake-dwg".getBytes()));
        when(floorPlanParserRegistry.resolve(FloorPlanFileType.CAD)).thenReturn(cadParser);
        when(cadParser.parse(any())).thenThrow(new BusinessException("CAD 图纸解析失败: 无房间标签"));

        asyncTaskProcessor.processFloorPlanAnalysis("TASK-3", "FPA-1", null, null, "cad/fp.dwg", false);

        verify(floorPlanService).finalizeParseFailure(eq("FPA-1"), eq(2),
            org.mockito.ArgumentMatchers.contains("CAD 图纸解析失败"));
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("failed");
    }

    /** P0-2 防回归：源文件读取失败（DWG 缺失）同样回写 Revision FAILED + working 回退。 */
    @Test
    void cadTask_sourceFileMissing_shouldFinalizeFailureRevision() throws Exception {
        AsyncTask task = task("TASK-4", "{\"analysisId\":\"FPA-1\",\"analysisRevision\":2}");
        when(asyncTaskMapper.selectById("TASK-4")).thenReturn(task);
        when(storageService.get("cad/missing.dwg"))
            .thenThrow(new java.io.IOException("object not found"));

        asyncTaskProcessor.processFloorPlanAnalysis("TASK-4", "FPA-1", null, null,
            "cad/missing.dwg", false);

        verify(floorPlanService).finalizeParseFailure(eq("FPA-1"), eq(2),
            org.mockito.ArgumentMatchers.contains("读取户型图失败"));
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper, org.mockito.Mockito.atLeastOnce()).updateById(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getStatus()).isEqualTo("failed");
    }

    private AsyncTask task(String taskId, String inputData) {
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setStatus("processing");
        task.setInputData(inputData);
        return task;
    }
}
