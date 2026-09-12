package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.dto.response.DocumentImportSubmitResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.DocumentImportBatch;
import com.rsdp.entity.ImageAssets;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.DocumentImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PdfImportService} 单元测试（阶段 3.1：异步批次化）。
 *
 * <p>提交接口（importPdf）只做校验 + 落文件 + 建批次/任务后立即返回；
 * 实际导入逻辑在 executeImport（由 AsyncTaskProcessor 异步调用）中验证。</p>
 */
@ExtendWith(MockitoExtension.class)
class PdfImportServiceTest {

    @Mock
    private VisionService visionService;

    @Mock
    private ProductService productService;

    @Mock
    private DocumentImportBatchMapper batchMapper;

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private AsyncTaskProcessor asyncTaskProcessor;

    @InjectMocks
    private PdfImportService pdfImportService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        setField("maxFileSizeMb", 100);
        setField("maxPages", 100);
        setField("renderDpi", 72f);
        setField("detectBatchSize", 5);
        setField("outputQuality", 0.9f);
        setField("embeddedMinAreaRatio", 0.20);
        setField("embeddedMinPixelEdge", 200);
        setField("objectMapper", objectMapper);
    }

    // ==================== 提交接口（立即返回） ====================

    @Test
    void importPdf_shouldCreateBatchAndReturnImmediately() throws IOException {
        byte[] pdfBytes = createPdfBytes(2);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);
        when(storageService.store(any(), anyString(), anyLong(), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));

        DocumentImportSubmitResult result = pdfImportService.importPdf(file, "SF");

        // 立即返回 batchId，不做任何渲染/AI 检测/建档
        assertThat(result.getBatchId()).isNotBlank();
        verify(visionService, never()).detectPageRegions(any(), any());
        verify(productService, never()).createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any());

        // 原始 PDF 落存储
        verify(storageService).store(any(), eq("document-imports/" + result.getBatchId() + ".pdf"),
            eq((long) pdfBytes.length), eq("application/pdf"));

        // 批次落库：pending + 总页数 + 品类提示
        ArgumentCaptor<DocumentImportBatch> batchCaptor = ArgumentCaptor.forClass(DocumentImportBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        DocumentImportBatch batch = batchCaptor.getValue();
        assertThat(batch.getBatchId()).isEqualTo(result.getBatchId());
        assertThat(batch.getStatus()).isEqualTo("pending");
        assertThat(batch.getTotalPages()).isEqualTo(2);
        assertThat(batch.getCategoryHint()).isEqualTo("SF");
        assertThat(batch.getStoragePath()).isEqualTo("document-imports/" + result.getBatchId() + ".pdf");

        // 异步任务落库：task_type=document_import，input_data 含 batchId
        ArgumentCaptor<AsyncTask> taskCaptor = ArgumentCaptor.forClass(AsyncTask.class);
        verify(asyncTaskMapper).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getTaskType()).isEqualTo("document_import");
        assertThat(taskCaptor.getValue().getInputData()).contains(result.getBatchId());

        // 投递异步处理（单元测试无活动事务，直接触发）
        verify(asyncTaskProcessor).processDocumentImport(taskCaptor.getValue().getTaskId(), result.getBatchId());
    }

    @Test
    void importPdf_shouldRejectOverMaxPages() throws IOException {
        // 正式上限 100 页：101 页拒绝
        byte[] pdfBytes = createPdfBytes(101);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);

        assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PDF 页数不能超过 100 页");
    }

    @Test
    void importPdf_shouldAcceptExactlyMaxPages() throws IOException {
        byte[] pdfBytes = createPdfBytes(100);
        MockMultipartFile file = new MockMultipartFile("file", "catalog.pdf", "application/pdf", pdfBytes);
        when(storageService.store(any(), anyString(), anyLong(), anyString()))
            .thenAnswer(inv -> inv.getArgument(1));

        DocumentImportSubmitResult result = pdfImportService.importPdf(file, null);

        assertThat(result.getBatchId()).isNotBlank();
        ArgumentCaptor<DocumentImportBatch> batchCaptor = ArgumentCaptor.forClass(DocumentImportBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        assertThat(batchCaptor.getValue().getTotalPages()).isEqualTo(100);
    }

    @Test
    void importPdf_shouldRejectOverMaxFileSize() {
        // 正式上限 100MB：超限拒绝（mock 文件大小，不构造真实大文件）
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(100L * 1024 * 1024 + 1);

        assertThatThrownBy(() -> pdfImportService.importPdf(file, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("PDF 文件大小超过限制");
    }

    // ==================== 批次执行（异步） ====================

    @Test
    void executeImport_shouldCreateEntriesForProductPages() throws IOException {
        DocumentImportBatch batch = setupBatch("B1", createPdfBytes(2));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));
        DocumentProductRegion coverPage = new DocumentProductRegion();
        coverPage.setPageType("cover");
        coverPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage, coverPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST01", "taskId", "TASK-TEST01"));

        DocumentImportBatch result = pdfImportService.executeImport("B1");

        assertThat(result.getStatus()).isEqualTo("done");
        assertThat(result.getProcessedPages()).isEqualTo(2);
        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getDetectedProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailCount()).isEqualTo(0);
        assertThat(result.getRspuIds()).contains("RSPU-TEST01");
        assertThat(result.getTaskIds()).contains("TASK-TEST01");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void executeImport_shouldUseCategoryHintWhenAiReturnsNull() throws IOException {
        DocumentImportBatch batch = setupBatch("B2", createPdfBytes(1));
        batch.setCategoryHint("TB");

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), null, null, null)
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), eq("TB"), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST02", "taskId", "TASK-TEST02"));

        DocumentImportBatch result = pdfImportService.executeImport("B2");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST02");
    }

    @Test
    void executeImport_shouldPassNearbyTextToEntry() throws IOException {
        setupBatch("B3", createPdfBytes(1));

        OcrResult nearbyText = new OcrResult();
        nearbyText.setProductName("兰卡沙发");
        nearbyText.setModelNumber("LK-2450");

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", nearbyText, null)
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST04", "taskId", "TASK-TEST04"));

        pdfImportService.executeImport("B3");

        ArgumentCaptor<OcrResult> ocrCaptor = ArgumentCaptor.forClass(OcrResult.class);
        verify(productService).createEntryFromStream(any(), anyString(), anyLong(), anyString(),
            ocrCaptor.capture(), any());
        assertThat(ocrCaptor.getValue().getProductName()).isEqualTo("兰卡沙发");
        assertThat(ocrCaptor.getValue().getModelNumber()).isEqualTo("LK-2450");
    }

    @Test
    void executeImport_shouldHandleNoProductPages() throws IOException {
        setupBatch("B4", createPdfBytes(1));

        DocumentProductRegion unknownPage = new DocumentProductRegion();
        unknownPage.setPageType("cover");
        unknownPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(unknownPage));

        DocumentImportBatch result = pdfImportService.executeImport("B4");

        // 无产品页且无失败明细 → done（空批次是合法结果）
        assertThat(result.getStatus()).isEqualTo("done");
        assertThat(result.getProcessedPages()).isEqualTo(1);
        assertThat(result.getProductPages()).isEqualTo(0);
        assertThat(result.getDetectedProducts()).isEqualTo(0);
        assertThat(result.getSuccessCount()).isEqualTo(0);
    }

    @Test
    void executeImport_shouldRetryUnknownPageIndividually() throws IOException {
        setupBatch("B5", createPdfBytes(1));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));

        // 批检测整体失败 → 整页降级 unknown；单页重试时恢复为产品页
        when(visionService.detectPageRegions(any(), any()))
            .thenThrow(new RuntimeException("AI 服务超时"))
            .thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST03", "taskId", "TASK-TEST03"));

        DocumentImportBatch result = pdfImportService.executeImport("B5");

        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST03");
    }

    @Test
    void executeImport_shouldUseEmbeddedImageWhenAiDetectsNoProduct() throws IOException {
        // PDF 含一张大面积嵌入产品图；AI 判定为产品页但没检出任何 bbox
        // → 嵌入图直取兜底，仍能创建录入任务（完整度保障）
        setupBatch("B6", createPdfWithLargeEmbeddedImage());

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of());

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST04", "taskId", "TASK-TEST04"));

        DocumentImportBatch result = pdfImportService.executeImport("B6");

        assertThat(result.getProductPages()).isEqualTo(1);
        assertThat(result.getDetectedProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST04");
    }

    @Test
    void executeImport_shouldSkipSceneProductsWithoutText() throws IOException {
        // AI 标记 imageKind=scene 且无说明文字的场景点缀产品不建档，同页单品图照常录入
        setupBatch("B7", createPdfBytes(1));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone"),
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.5, 0.5, 0.4, 0.4), "SF", null, "scene")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST05", "taskId", "TASK-TEST05"));

        DocumentImportBatch result = pdfImportService.executeImport("B7");

        assertThat(result.getDetectedProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST05");
    }

    @Test
    void executeImport_shouldKeepSceneProductsWithText() throws IOException {
        // 场景中完整可见且带说明文字（nearbyText）的产品保留，照常裁剪录入
        setupBatch("B8", createPdfBytes(1));

        OcrResult sceneText = new OcrResult();
        sceneText.setProductName("云朵沙发");
        sceneText.setDimensionText("2200×950×860mm");

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone"),
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.5, 0.5, 0.4, 0.4), "SF", sceneText, "scene")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST07", "taskId", "TASK-TEST07"));

        DocumentImportBatch result = pdfImportService.executeImport("B8");

        assertThat(result.getDetectedProducts()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
    }

    @Test
    void executeImport_shouldSkipSceneEmbeddedImageAndFallbackToAiCrop() throws IOException {
        // 嵌入大图边框带杂乱（疑似场景图）→ 剔除后嵌入图数量不足，回落 AI bbox 裁剪路径
        setupBatch("B9", createPdfWithSceneLikeEmbeddedImage());

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, "standalone")
        ));

        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST06", "taskId", "TASK-TEST06"));

        DocumentImportBatch result = pdfImportService.executeImport("B9");

        assertThat(result.getDetectedProducts()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST06");
    }

    @Test
    void executeImport_shouldRetryNullBBoxPageIndividually() throws IOException {
        // 批检测返回 product 页但产品 bbox 全为 null（模型输出格式抖动）→ 单页重试恢复
        setupBatch("B10", createPdfBytes(1));

        DocumentProductRegion brokenPage = new DocumentProductRegion();
        brokenPage.setPageType("product");
        brokenPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(null, "SF", null, null)
        ));
        DocumentProductRegion recoveredPage = new DocumentProductRegion();
        recoveredPage.setPageType("product");
        recoveredPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));

        when(visionService.detectPageRegions(any(), any()))
            .thenReturn(List.of(brokenPage))
            .thenReturn(List.of(recoveredPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST08", "taskId", "TASK-TEST08"));

        DocumentImportBatch result = pdfImportService.executeImport("B10");

        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getRspuIds()).contains("RSPU-TEST08");
        verify(visionService, times(2)).detectPageRegions(any(), any());
    }

    // ==================== contentHash 查重跳过 ====================

    @Test
    void executeImport_shouldSkipDuplicateByContentHash() throws IOException {
        setupBatch("B11", createPdfBytes(1));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));
        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));

        // 图片字节 hash 命中未软删 image_assets → 跳过建档
        ImageAssets duplicate = new ImageAssets();
        duplicate.setImageId("IMG-DUP01");
        duplicate.setRspuId("RSPU-DUP01");
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicate);

        DocumentImportBatch result = pdfImportService.executeImport("B11");

        // 不建档，记"已存在跳过"；异步线程无 SecurityContext → 中性文案（不含品名/编码）
        verify(productService, never()).createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any());
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getSkipCount()).isEqualTo(1);
        assertThat(result.getStatus()).isEqualTo("partial_success");
        assertThat(result.getFailures()).contains("已存在").contains("跳过建档")
            .doesNotContain("RSPU-DUP01");
    }

    @Test
    void executeImport_shouldIncludeProductInfoInSkipMessageForPlatformStaff() throws IOException {
        setupBatch("B12", createPdfBytes(1));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null)
        ));
        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));

        ImageAssets duplicate = new ImageAssets();
        duplicate.setImageId("IMG-DUP02");
        duplicate.setRspuId("RSPU-DUP02");
        when(imageAssetsMapper.selectByContentHash(anyString())).thenReturn(duplicate);
        com.rsdp.entity.RspuMaster rspu = new com.rsdp.entity.RspuMaster();
        rspu.setRspuId("RSPU-DUP02");
        rspu.setProductName("云朵沙发");
        rspu.setRspuCode("FS-MC-001-M");
        when(rspuMapper.selectById("RSPU-DUP02")).thenReturn(rspu);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isPlatformStaff).thenReturn(true);
            DocumentImportBatch result = pdfImportService.executeImport("B12");

            assertThat(result.getSkipCount()).isEqualTo(1);
            assertThat(result.getFailures()).contains("云朵沙发").contains("FS-MC-001-M");
        }
    }

    // ==================== 分块逐页流式 ====================

    @Test
    void executeImport_shouldProcessPagesInChunksWithoutFullResidency() throws IOException {
        // 12 页 PDF、检测批大小 5：分块应为 [5,5,2]，任何一块都不持有全量 12 页位图
        setupBatch("B13", createPdfBytes(12));

        // 每次块检测返回与输入等长的 cover 页结果
        when(visionService.detectPageRegions(any(), any())).thenAnswer(inv -> {
            List<?> streams = inv.getArgument(0);
            List<DocumentProductRegion> regions = new ArrayList<>(streams.size());
            for (int i = 0; i < streams.size(); i++) {
                DocumentProductRegion region = new DocumentProductRegion();
                region.setPageType("cover");
                region.setProducts(List.of());
                regions.add(region);
            }
            return regions;
        });

        PdfImportService spyService = spy(pdfImportService);
        List<Integer> chunkSizes = new ArrayList<>();
        doAnswer(inv -> {
            int start = inv.getArgument(2);
            int end = inv.getArgument(3);
            chunkSizes.add(end - start);
            return inv.callRealMethod();
        }).when(spyService).processPageChunk(any(), any(), anyInt(), anyInt(), any());

        DocumentImportBatch result = spyService.executeImport("B13");

        assertThat(chunkSizes).containsExactly(5, 5, 2);
        assertThat(result.getProcessedPages()).isEqualTo(12);
        assertThat(result.getStatus()).isEqualTo("done");
        // 3 个块各检测一次，无单页重试（全部 cover 页无需重试）
        verify(visionService, times(3)).detectPageRegions(any(), any());
    }

    // ==================== 失败与致命错误 ====================

    @Test
    void executeImport_shouldFailBatchWhenStorageReadFails() throws IOException {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("B14");
        batch.setFileName("catalog.pdf");
        batch.setStoragePath("document-imports/B14.pdf");
        batch.setStatus("pending");
        batch.setTotalPages(2);
        when(batchMapper.selectById("B14")).thenReturn(batch);
        when(storageService.get("document-imports/B14.pdf")).thenThrow(new IOException("disk error"));

        DocumentImportBatch result = pdfImportService.executeImport("B14");

        assertThat(result.getStatus()).isEqualTo("failed");
        assertThat(result.getErrorMessage()).contains("读取原始 PDF 文件失败");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void executeImport_shouldRejectMissingBatch() {
        when(batchMapper.selectById("B-MISSING")).thenReturn(null);

        assertThatThrownBy(() -> pdfImportService.executeImport("B-MISSING"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("导入批次不存在");
    }

    @Test
    void executeImport_shouldMarkPartialSuccessWhenSomeProductsFail() throws IOException {
        setupBatch("B15", createPdfBytes(1));

        DocumentProductRegion productPage = new DocumentProductRegion();
        productPage.setPageType("product");
        productPage.setProducts(List.of(
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.1, 0.1, 0.4, 0.4), "SF", null, null),
            new DocumentProductRegion.PageProduct(new ProductBoundingBox(0.5, 0.5, 0.4, 0.4), "SF", null, null)
        ));
        when(visionService.detectPageRegions(any(), any())).thenReturn(List.of(productPage));
        when(productService.createEntryFromStream(any(), anyString(), anyLong(), anyString(), any(), any()))
            .thenReturn(Map.of("rspuId", "RSPU-TEST09", "taskId", "TASK-TEST09"))
            .thenThrow(new RuntimeException("存储故障"));

        DocumentImportBatch result = pdfImportService.executeImport("B15");

        assertThat(result.getStatus()).isEqualTo("partial_success");
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.getFailures()).contains("产品录入失败");
    }

    // ==================== 批次归属校验与结果查询 ====================

    @Test
    void getAccessibleBatch_shouldAllowOwner() {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("B20");
        batch.setCreatedBy("user-1");
        when(batchMapper.selectById("B20")).thenReturn(batch);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(false);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-1");
            assertThat(pdfImportService.getAccessibleBatch("B20")).isSameAs(batch);
        }
    }

    @Test
    void getAccessibleBatch_shouldAllowAdmin() {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("B21");
        batch.setCreatedBy("user-1");
        when(batchMapper.selectById("B21")).thenReturn(batch);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(true);
            assertThat(pdfImportService.getAccessibleBatch("B21")).isSameAs(batch);
        }
    }

    @Test
    void getAccessibleBatch_shouldRejectOtherUser() {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("B22");
        batch.setCreatedBy("user-1");
        when(batchMapper.selectById("B22")).thenReturn(batch);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(false);
            mocked.when(SecurityOperatorContext::currentUserId).thenReturn("user-2");
            assertThatThrownBy(() -> pdfImportService.getAccessibleBatch("B22"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("无权访问该导入批次");
        }
    }

    @Test
    void getAccessibleBatch_shouldRejectMissingBatch() {
        when(batchMapper.selectById("B-MISSING")).thenReturn(null);

        assertThatThrownBy(() -> pdfImportService.getAccessibleBatch("B-MISSING"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("导入批次不存在");
    }

    @Test
    void getBatchResult_shouldMapProgressAndDetails() {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId("B23");
        batch.setCreatedBy("user-1");
        batch.setStatus("partial_success");
        batch.setTotalPages(10);
        batch.setProcessedPages(10);
        batch.setProductPages(8);
        batch.setDetectedProducts(9);
        batch.setSuccessCount(7);
        batch.setFailCount(1);
        batch.setSkipCount(1);
        batch.setTaskIds("[\"TASK-1\",\"TASK-2\"]");
        batch.setRspuIds("[\"RSPU-1\",\"RSPU-2\"]");
        batch.setFailures("[{\"pageIndex\":3,\"reason\":\"产品录入失败: x\"},{\"pageIndex\":5,\"reason\":\"产品图已存在，跳过建档（避免重复录入）\"}]");
        when(batchMapper.selectById("B23")).thenReturn(batch);

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(SecurityOperatorContext::isCurrentUserAdmin).thenReturn(true);
            DocumentImportResult result = pdfImportService.getBatchResult("B23");

            assertThat(result.getStatus()).isEqualTo("partial_success");
            assertThat(result.getTotalPages()).isEqualTo(10);
            assertThat(result.getProcessedPages()).isEqualTo(10);
            assertThat(result.getProductPages()).isEqualTo(8);
            assertThat(result.getTotalProducts()).isEqualTo(9);
            assertThat(result.getSuccessCount()).isEqualTo(7);
            assertThat(result.getFailedCount()).isEqualTo(1);
            assertThat(result.getSkippedCount()).isEqualTo(1);
            assertThat(result.getTaskIds()).containsExactly("TASK-1", "TASK-2");
            assertThat(result.getRspuIds()).containsExactly("RSPU-1", "RSPU-2");
            assertThat(result.getFailures()).hasSize(2);
            assertThat(result.getFailures().get(1).getReason()).contains("已存在");
        }
    }

    // ==================== 纯函数测试（与同步版一致，保留） ====================

    @Test
    void expandBox_shouldExpandRelativeToBoxSize() {
        // 相对 bbox 自身宽高的 5%：0.4 宽的框水平外扩 0.02，而不是相对整页的固定 0.03
        ProductBoundingBox expanded =
            PdfImportService.expandBox(new ProductBoundingBox(0.2, 0.2, 0.4, 0.4), 0.05);

        assertThat(expanded.getX()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getY()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getWidth()).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(expanded.getHeight()).isCloseTo(0.44, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void expandBox_shouldClampAtPageEdges() {
        // 左上边缘：外扩后 x/y 钳制为 0，宽高不超过页面
        ProductBoundingBox edge =
            PdfImportService.expandBox(new ProductBoundingBox(0.0, 0.0, 0.5, 0.5), 0.05);
        assertThat(edge.getX()).isEqualTo(0.0);
        assertThat(edge.getY()).isEqualTo(0.0);
        assertThat(edge.getWidth()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-9));

        // 右下边缘：x+w 不超过 1
        ProductBoundingBox far =
            PdfImportService.expandBox(new ProductBoundingBox(0.9, 0.9, 0.1, 0.1), 0.05);
        assertThat(far.getX() + far.getWidth()).isLessThanOrEqualTo(1.0);
        assertThat(far.getY() + far.getHeight()).isLessThanOrEqualTo(1.0);
    }

    @Test
    void recoverCutEdges_shouldRecoverContinuousProductBelowCore() {
        // 核心框底边切进产品（abs 300），产品实际延伸到 abs 349，下方是隔断+说明文字
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 250, new java.awt.Color(30, 60, 120));
        for (int x = 100; x <= 170; x += 35) {
            fillRectOnPage(page, x, 460, 25, 6, new java.awt.Color(40, 40, 40));
        }
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 1.0 / 3);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        // 底边恢复到产品真实底部 abs 350（隔断前的最后一行内容），文字区不被并入
        assertThat(recovered.getY() + recovered.getHeight())
            .isCloseTo(350.0 / 600, org.assertj.core.data.Offset.offset(0.01));
        assertThat(recovered.getX()).isEqualTo(core.getX());
        assertThat(recovered.getWidth()).isEqualTo(core.getWidth());
    }

    @Test
    void recoverCutEdges_shouldNotExtendWhenEdgeIsBackground() {
        // 核心框底边落在产品之外的空白区（框已包全产品）→ 无切断信号，原样返回
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 200, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 0.5);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        assertThat(recovered.getY() + recovered.getHeight()).isEqualTo(1.0 / 6 + 0.5);
    }

    @Test
    void recoverCutEdges_shouldStopBeforeSiblingBox() {
        // 下方延伸撞上兄弟产品框（abs 350 起）→ 在兄弟框边界前停止，不侵占其区域
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 200, 400, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 0.5, 1.0 / 3);
        ProductBoundingBox sibling = new ProductBoundingBox(0.25, 350.0 / 600, 0.5, 0.2);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of(sibling));

        assertThat(recovered.getY() + recovered.getHeight())
            .isLessThanOrEqualTo(350.0 / 600 + 0.001);
    }

    @Test
    void recoverCutEdges_shouldRecoverRightEdge() {
        // 核心框右边切进产品（abs 300），产品向右延伸到 abs 349（在外延上限内）
        java.awt.image.BufferedImage page = createTestPage(400, 600, java.awt.Color.WHITE);
        fillRectOnPage(page, 100, 100, 250, 200, new java.awt.Color(30, 60, 120));
        ProductBoundingBox core = new ProductBoundingBox(0.25, 1.0 / 6, 200.0 / 400, 200.0 / 600);

        ProductBoundingBox recovered = PdfImportService.recoverCutEdges(page, core, java.util.List.of());

        assertThat(recovered.getX() + recovered.getWidth())
            .isCloseTo(350.0 / 400, org.assertj.core.data.Offset.offset(0.01));
    }

    // ==================== 测试辅助 ====================

    /**
     * 准备待处理批次：批次落库桩 + 存储读取桩（executeImport 从存储读回原始 PDF）。
     */
    private DocumentImportBatch setupBatch(String batchId, byte[] pdfBytes) throws IOException {
        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId(batchId);
        batch.setFileName("catalog.pdf");
        batch.setStoragePath("document-imports/" + batchId + ".pdf");
        batch.setStatus("pending");
        when(batchMapper.selectById(batchId)).thenReturn(batch);
        lenient().when(storageService.get(batch.getStoragePath()))
            .thenReturn(new ByteArrayInputStream(pdfBytes));
        return batch;
    }

    private java.awt.image.BufferedImage createTestPage(int width, int height, java.awt.Color bg) {
        java.awt.image.BufferedImage image =
            new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(bg);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private void fillRectOnPage(java.awt.image.BufferedImage image, int x, int y, int w, int h,
                                java.awt.Color color) {
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(x, y, w, h);
        g.dispose();
    }

    private void setField(String name, Object value) throws Exception {
        Field field = PdfImportService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(pdfImportService, value);
    }

    private byte[] createPdfBytes(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createPdfWithLargeEmbeddedImage() throws IOException {
        try (PDDocument document = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            document.addPage(page);

            // 白底 + 中心产品色块：模拟单品图嵌入（边框带近白，不会被场景规则误杀）
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(300, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 300, 400);
            g.setColor(new java.awt.Color(60, 120, 180));
            g.fillRect(60, 100, 180, 200);
            g.dispose();

            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                     new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                // 绘制尺寸 400x600 点，面积占比约 48%，满足大图阈值
                contentStream.drawImage(pdImage, 50, 100, 400, 600);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private byte[] createPdfWithSceneLikeEmbeddedImage() throws IOException {
        try (PDDocument document = new PDDocument()) {
            org.apache.pdfbox.pdmodel.PDPage page =
                new org.apache.pdfbox.pdmodel.PDPage(org.apache.pdfbox.pdmodel.common.PDRectangle.A4);
            document.addPage(page);

            // 上墙下地 + 杂色家具：边框带亮度方差大，触发场景图规则
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(300, 400, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = image.createGraphics();
            g.setColor(new java.awt.Color(210, 200, 180));
            g.fillRect(0, 0, 300, 200);
            g.setColor(new java.awt.Color(70, 50, 35));
            g.fillRect(0, 200, 300, 200);
            g.setColor(new java.awt.Color(40, 90, 60));
            g.fillRect(100, 150, 100, 120);
            g.dispose();

            org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject pdImage =
                org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory.createFromImage(document, image);
            try (org.apache.pdfbox.pdmodel.PDPageContentStream contentStream =
                     new org.apache.pdfbox.pdmodel.PDPageContentStream(document, page)) {
                contentStream.drawImage(pdImage, 50, 100, 400, 600);
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }
}
