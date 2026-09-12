package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.dto.response.DocumentImportFailure;
import com.rsdp.dto.response.DocumentImportResult;
import com.rsdp.dto.response.DocumentImportSubmitResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.DocumentImportBatch;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.DocumentImportBatchMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.ContentHashes;
import com.rsdp.util.ImageBackgroundAnalyzer;
import com.rsdp.util.ImageWhitespaceTrimmer;
import com.rsdp.util.PdfEmbeddedImageExtractor;
import com.rsdp.util.PdfFileValidator;
import com.rsdp.util.PdfRenderer;
import com.rsdp.util.ProductBoxRefiner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.rsdp.util.IdGenerator;

/**
 * PDF 产品目录批量导入服务（阶段 3.1：异步批次化）。
 *
 * <p>提交接口只做校验 + 原始文件落存储 + 建批次（pending）+ 建异步任务后立即返回 batchId；
 * 批处理复用 async_task 体系（task_type=document_import）由 {@link AsyncTaskProcessor} 异步执行：
 * 按检测批次大小分块逐页渲染（渲染一块 → AI 检测 → 裁剪产品图 → 释放该块位图），
 * 全程不保留全量页位图；逐产品建档前按图片字节 contentHash 查重，命中跳过建档；
 * 每处理完一页回写批次进度，前端按 batchId 轮询。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfImportService {

    /** 批次状态：待处理 */
    public static final String STATUS_PENDING = "pending";
    /** 批次状态：处理中 */
    public static final String STATUS_PROCESSING = "processing";
    /** 批次状态：全部成功 */
    public static final String STATUS_DONE = "done";
    /** 批次状态：部分成功（含失败/跳过明细） */
    public static final String STATUS_PARTIAL_SUCCESS = "partial_success";
    /** 批次状态：失败 */
    public static final String STATUS_FAILED = "failed";

    /** 文档导入异步任务类型（async_task.task_type） */
    public static final String TASK_TYPE_DOCUMENT_IMPORT = "document_import";

    private final VisionService visionService;
    private final ProductService productService;
    private final DocumentImportBatchMapper batchMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final RspuMapper rspuMapper;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final AsyncTaskProcessor asyncTaskProcessor;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.document-import.pdf.max-file-size-mb:100}")
    private int maxFileSizeMb;

    // PDF 导入正式上限 100 页 / 100MB（2026-09-11 决策点③定口径）：
    // 导入已异步批次化 + 分块逐页流式渲染，页位图不再全量驻留堆内存。
    // 可通过 rsdp.document-import.pdf.max-pages 配置覆盖
    @Value("${rsdp.document-import.pdf.max-pages:100}")
    private int maxPages;

    @Value("${rsdp.document-import.pdf.render-dpi:200}")
    private float renderDpi;

    @Value("${rsdp.document-import.pdf.detect-batch-size:5}")
    private int detectBatchSize;

    @Value("${rsdp.document-import.pdf.output-quality:0.9}")
    private float outputQuality;

    /**
     * 嵌入图直取：图片绘制面积占页面面积的最小比例。
     */
    @Value("${rsdp.document-import.pdf.embedded-image.min-area-ratio:0.20}")
    private double embeddedMinAreaRatio;

    /**
     * 嵌入图直取：图片原始像素的最小边长。
     */
    @Value("${rsdp.document-import.pdf.embedded-image.min-pixel-edge:200}")
    private int embeddedMinPixelEdge;

    /**
     * AI 检测用图长边上限。qwen-vl 支持高分辨率输入，
     * 2688px 相比 1568px 能显著提升大幅面页面（4000px+）上
     * 白色/浅色产品（白底低对比）的 bbox 边界精度，代价是检测耗时与流量增加。
     */
    private static final int DETECT_IMAGE_MAX_EDGE = 2688;

    /**
     * 裁剪前 bbox 外扩比例（相对 bbox 自身宽高）。
     *
     * <p>注意：历史上该值是相对整页宽高的 0.03（200DPI A4 约 74~105px），
     * 对小产品框过度外扩、容易把旁边说明文字框进来；改为相对 bbox 后
     * 小框少扩、大框多扩。收紧/文字带重裁已锚定核心框（不切入 AI 原始框），
     * 外扩到 10% 以更好容忍 AI 框低估（浅色/深色产品底部被框小），
     * 多出来的边距由收紧和文字带重裁清掉。</p>
     */
    private static final double BOX_EXPAND_RATIO = 0.10;

    /**
     * 四边统一留白比例（相对内容最长边，产品居中重排）。
     */
    private static final double CROP_PAD_RATIO = 0.05;

    /**
     * 提交 PDF 导入：校验 + 原始文件落存储 + 建批次（pending）+ 建异步任务后立即返回。
     *
     * @param file         PDF 文件
     * @param categoryHint 品类提示，可为空
     * @return 提交结果（仅含 batchId，处理进度走批次查询接口轮询）
     * @throws IOException 文件读取/存储失败
     */
    @Transactional
    public DocumentImportSubmitResult importPdf(MultipartFile file, String categoryHint) throws IOException {
        long maxSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        int totalPages = PdfFileValidator.validate(file, maxSizeBytes, maxPages);
        byte[] pdfBytes = file.getBytes();

        String batchId = IdGenerator.batchId();
        // 原始 PDF 落存储：批处理异步执行时从存储读回，请求线程不持有字节
        String objectKey = "document-imports/" + batchId + ".pdf";
        String storagePath;
        try (InputStream in = new ByteArrayInputStream(pdfBytes)) {
            storagePath = storageService.store(in, objectKey, pdfBytes.length, "application/pdf");
        } catch (IOException e) {
            log.error("保存原始 PDF 文件失败，batchId={}", batchId, e);
            throw new BusinessException("保存原始 PDF 文件失败");
        }
        registerStorageRollbackCleanup(List.of(storagePath));

        DocumentImportBatch batch = new DocumentImportBatch();
        batch.setBatchId(batchId);
        batch.setFileName(file.getOriginalFilename());
        batch.setStoragePath(storagePath);
        batch.setStatus(STATUS_PENDING);
        batch.setTotalPages(totalPages);
        batch.setProcessedPages(0);
        batch.setProductPages(0);
        batch.setDetectedProducts(0);
        batch.setSuccessCount(0);
        batch.setFailCount(0);
        batch.setSkipCount(0);
        if (StringUtils.hasText(categoryHint)) {
            // category_hint 列宽 VARCHAR(16)，超长截断防御（对齐 Excel 导入 saveBatch 口径）
            String hint = categoryHint.trim().toUpperCase();
            batch.setCategoryHint(hint.length() > 16 ? hint.substring(0, 16) : hint);
        }
        batch.setCreatedBy(SecurityOperatorContext.currentUserId());
        batch.setCreatedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.insert(batch);
        auditLogService.logCreate("document_import_batch", batchId, batch, SecurityOperatorContext.currentUsername());

        // 建异步任务（task_type=document_import）：批处理由 AsyncTaskProcessor 认领执行
        String taskId = IdGenerator.taskId();
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType(TASK_TYPE_DOCUMENT_IMPORT);
        task.setStatus(STATUS_PENDING);
        task.setProgress(0);
        task.setInputData(objectMapper.writeValueAsString(Map.of(
            "batchId", batchId,
            "objectKey", storagePath,
            "fileName", file.getOriginalFilename() != null ? file.getOriginalFilename() : "",
            "categoryHint", batch.getCategoryHint() != null ? batch.getCategoryHint() : ""
        )));
        task.setCreatedBy(SecurityOperatorContext.currentUsername());
        task.setCreatedAt(LocalDateTime.now());
        asyncTaskMapper.insert(task);

        triggerAsyncImport(taskId, batchId);
        log.info("PDF 导入批次已创建，batchId={}，taskId={}，总页数={}", batchId, taskId, totalPages);
        return new DocumentImportSubmitResult(batchId);
    }

    /**
     * 执行导入批次（由 {@link AsyncTaskProcessor#processDocumentImport} 异步调用）。
     *
     * <p>分块逐页流式处理：每次只渲染 {@code detectBatchSize} 页位图，检测 + 裁剪建档完成后
     * 释放该块再渲染下一块，全程不保留全量页位图；每处理完一页回写批次进度。</p>
     *
     * @param batchId 批次 ID
     * @return 终态批次实体（status 为 done/partial_success/failed）
     * @throws BusinessException 批次不存在或处理发生致命错误（批次已置 failed）
     */
    public DocumentImportBatch executeImport(String batchId) {
        long start = System.currentTimeMillis();
        DocumentImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + batchId);
        }
        batch.setStatus(STATUS_PROCESSING);
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);

        byte[] pdfBytes;
        try (InputStream in = storageService.get(batch.getStoragePath())) {
            pdfBytes = in.readAllBytes();
        } catch (Exception e) {
            return failBatchFatally(batch, "读取原始 PDF 文件失败: " + e.getMessage(), e);
        }

        ImportAccumulator acc = new ImportAccumulator();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int totalPages = document.getNumberOfPages();
            for (int chunkStart = 0; chunkStart < totalPages; chunkStart += detectBatchSize) {
                int chunkEnd = Math.min(chunkStart + detectBatchSize, totalPages);
                processPageChunk(document, batch, chunkStart, chunkEnd, acc);
            }
        } catch (Exception e) {
            return failBatchFatally(batch, "PDF 解析失败: " + e.getMessage(), e);
        }

        finalizeBatch(batch, acc);
        log.info("PDF 导入批次完成，batchId={}，总页数={}，产品页={}，产品数={}，成功={}，失败={}，跳过={}，总耗时 {}ms",
            batchId, batch.getTotalPages(), acc.productPages, acc.totalProducts,
            acc.successCount, acc.failCount, acc.skipCount, System.currentTimeMillis() - start);
        return batch;
    }

    /**
     * 查询批次并校验归属：仅批次创建者本人或平台 ADMIN 可访问（对齐 excel_import_batch 口径）。
     *
     * @param batchId 批次 ID
     * @return 批次实体
     * @throws BusinessException  批次不存在
     * @throws ForbiddenException 无权访问该批次
     */
    public DocumentImportBatch getAccessibleBatch(String batchId) {
        DocumentImportBatch batch = batchMapper.selectById(batchId);
        if (batch == null) {
            throw new BusinessException("导入批次不存在: " + batchId);
        }
        if (SecurityOperatorContext.isCurrentUserAdmin()) {
            return batch;
        }
        if (batch.getCreatedBy() == null
            || !batch.getCreatedBy().equals(SecurityOperatorContext.currentUserId())) {
            throw new ForbiddenException("无权访问该导入批次: " + batchId);
        }
        return batch;
    }

    /**
     * 查询批次状态/进度/结果（含 taskIds/rspuIds 配对，供前端继续轮询各产品识别任务）。
     *
     * @param batchId 批次 ID
     * @return 批次结果视图
     */
    public DocumentImportResult getBatchResult(String batchId) {
        DocumentImportBatch batch = getAccessibleBatch(batchId);
        DocumentImportResult result = new DocumentImportResult();
        result.setBatchId(batch.getBatchId());
        result.setStatus(batch.getStatus());
        result.setErrorMessage(batch.getErrorMessage());
        result.setTotalPages(valueOrZero(batch.getTotalPages()));
        result.setProcessedPages(valueOrZero(batch.getProcessedPages()));
        result.setProductPages(valueOrZero(batch.getProductPages()));
        result.setTotalProducts(valueOrZero(batch.getDetectedProducts()));
        result.setSuccessCount(valueOrZero(batch.getSuccessCount()));
        result.setFailedCount(valueOrZero(batch.getFailCount()));
        result.setSkippedCount(valueOrZero(batch.getSkipCount()));
        result.setTaskIds(readStringList(batch.getTaskIds()));
        result.setRspuIds(readStringList(batch.getRspuIds()));
        result.setFailures(readFailures(batch.getFailures()));
        return result;
    }

    // ==================== 批处理执行（分块逐页流式） ====================

    /**
     * 处理一个页块（最多 detectBatchSize 页）：渲染本块页位图 → AI 检测 → 逐页裁剪建档 →
     * 回写批次进度。方法返回后本块页位图即可被 GC，不驻留全量页位图。
     *
     * <p>package-private 以便测试通过 spy 验证分块流式行为。</p>
     */
    void processPageChunk(PDDocument document, DocumentImportBatch batch, int chunkStart, int chunkEnd,
                          ImportAccumulator acc) {
        // 逐页渲染：单页渲染失败只记录该页失败，不影响本块其余页
        List<BufferedImage> chunkImages = new ArrayList<>(chunkEnd - chunkStart);
        List<Integer> chunkIndexes = new ArrayList<>(chunkEnd - chunkStart);
        for (int i = chunkStart; i < chunkEnd; i++) {
            try {
                chunkImages.add(PdfRenderer.renderPage(document, i, renderDpi));
                chunkIndexes.add(i);
            } catch (Exception e) {
                log.error("PDF 第 {} 页渲染失败，batchId={}", i + 1, batch.getBatchId(), e);
                acc.failures.add(new DocumentImportFailure(i, "页面渲染失败: " + e.getMessage()));
                acc.pageResults.add(new PageResult(i, "render_failed", 0));
                acc.processedPages++;
            }
        }

        if (!chunkImages.isEmpty()) {
            // 块内批量 AI 检测（region.pageIndex 为块内相对序号，映射回绝对页码）
            List<DocumentProductRegion> regions = detectProductRegions(chunkImages);
            for (int k = 0; k < regions.size(); k++) {
                DocumentProductRegion region = regions.get(k);
                int pageIndex = chunkIndexes.get(k);
                if (region == null) {
                    region = new DocumentProductRegion();
                    region.setPageType("unknown");
                }
                region.setPageIndex(pageIndex);
                processPage(document, batch, pageIndex, chunkImages.get(k), region, acc);
            }
        }
        // 每处理完一个页块回写一次批次进度（块内逐页累计，块尾落库）
        flushProgress(batch, acc);
    }

    /**
     * 处理单页：产品页提取产品图并逐产品建档（含 contentHash 查重），非产品页只记页级结果。
     */
    private void processPage(PDDocument document, DocumentImportBatch batch, int pageIndex,
                             BufferedImage pageImage, DocumentProductRegion region, ImportAccumulator acc) {
        acc.processedPages++;
        // 注意：只按 pageType 判断，AI 判为产品页但漏检 bbox 时也要走嵌入图兜底
        if (!isProductPageType(region)) {
            acc.pageResults.add(new PageResult(pageIndex,
                region.getPageType() != null ? region.getPageType() : "unknown", 0));
            return;
        }
        acc.productPages++;
        List<BufferedImage> embeddedImages = extractEmbeddedImagesSafely(document, pageIndex, batch.getBatchId());
        List<ProductSource> sources = buildProductSources(region, embeddedImages,
            pageImage.getWidth(), pageImage.getHeight());
        if (sources.isEmpty()) {
            log.warn("产品页未提取到任何产品图（AI 漏检且无嵌入大图），batchId={}，pageIndex={}",
                batch.getBatchId(), pageIndex);
        }
        acc.totalProducts += sources.size();
        for (ProductSource source : sources) {
            try {
                EntryInfo entryInfo = createEntryFromSource(batch.getBatchId(), pageImage, source,
                    batch.getCategoryHint(), pageIndex, acc, batch.getCreatedBy());
                if (entryInfo != null && entryInfo.rspuId != null) {
                    acc.rspuIds.add(entryInfo.rspuId);
                    acc.taskIds.add(entryInfo.taskId);
                    acc.successCount++;
                }
            } catch (Exception e) {
                acc.failCount++;
                log.warn("产品图提取或录入失败，batchId={}，pageIndex={}", batch.getBatchId(), pageIndex, e);
                acc.failures.add(new DocumentImportFailure(pageIndex, "产品录入失败: " + e.getMessage()));
            }
        }
        acc.pageResults.add(new PageResult(pageIndex, "product", sources.size()));
    }

    /**
     * 抽取单页嵌入大图，失败（含 OOM）时降级为空列表（纯 AI 裁剪路径）。
     */
    private List<BufferedImage> extractEmbeddedImagesSafely(PDDocument document, int pageIndex, String batchId) {
        try {
            List<BufferedImage> embedded =
                PdfEmbeddedImageExtractor.extractPageImages(document, pageIndex, embeddedMinAreaRatio,
                    embeddedMinPixelEdge);
            if (!embedded.isEmpty()) {
                log.info("PDF 第 {} 页抽取到 {} 张大嵌入图，batchId={}", pageIndex + 1, embedded.size(), batchId);
            }
            return embedded;
        } catch (OutOfMemoryError e) {
            // 防御性兜底：单图解码已有像素上限拦截，理论上不应到达；一旦发生必须让主流程继续
            log.error("PDF 第 {} 页嵌入图抽取内存不足，降级为纯 AI 裁剪路径，batchId={}", pageIndex + 1, batchId);
            return List.of();
        } catch (Exception e) {
            log.warn("PDF 第 {} 页嵌入图抽取失败，降级为纯 AI 裁剪路径，batchId={}", pageIndex + 1, batchId, e);
            return List.of();
        }
    }

    /**
     * 批次进度落库（页块尾调用）：进度字段 + 明细 JSONB 全量刷新。
     */
    private void flushProgress(DocumentImportBatch batch, ImportAccumulator acc) {
        batch.setProcessedPages(acc.processedPages);
        batch.setProductPages(acc.productPages);
        batch.setDetectedProducts(acc.totalProducts);
        batch.setSuccessCount(acc.successCount);
        batch.setFailCount(acc.failCount);
        batch.setSkipCount(acc.skipCount);
        batch.setFailures(toJson(acc.failures));
        batch.setPageResults(toJson(acc.pageResults));
        batch.setTaskIds(toJson(acc.taskIds));
        batch.setRspuIds(toJson(acc.rspuIds));
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
    }

    /**
     * 批次正常收尾：无失败明细 → done；有成功/跳过 → partial_success；全部失败 → failed。
     */
    private void finalizeBatch(DocumentImportBatch batch, ImportAccumulator acc) {
        flushProgress(batch, acc);
        String finalStatus;
        String errorMessage = null;
        if (acc.failCount == 0 && acc.failures.isEmpty()) {
            finalStatus = STATUS_DONE;
        } else if (acc.successCount > 0 || acc.skipCount > 0) {
            finalStatus = STATUS_PARTIAL_SUCCESS;
        } else {
            finalStatus = STATUS_FAILED;
            errorMessage = "未成功建档任何产品"
                + (acc.failures.isEmpty() ? "" : "，首个失败原因: " + acc.failures.get(0).getReason());
        }
        batch.setStatus(finalStatus);
        batch.setErrorMessage(errorMessage);
        batch.setCompletedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        auditLogService.logUpdate("document_import_batch", batch.getBatchId(), null, batch, resolveBatchOperator(batch));
    }

    /**
     * 致命失败收尾：批次置 failed 并写明原因，随后抛出让任务状态联动失败。
     */
    private DocumentImportBatch failBatchFatally(DocumentImportBatch batch, String message, Exception e) {
        log.error("PDF 导入批次失败，batchId={}: {}", batch.getBatchId(), message, e);
        batch.setStatus(STATUS_FAILED);
        batch.setErrorMessage(message);
        batch.setCompletedAt(LocalDateTime.now());
        batch.setUpdatedAt(LocalDateTime.now());
        batchMapper.updateById(batch);
        auditLogService.logUpdate("document_import_batch", batch.getBatchId(), null, batch, resolveBatchOperator(batch));
        return batch;
    }

    /**
     * 批次审计操作人：异步线程无 SecurityContext，取批次创建人（created_by 为 userId，
     * 审计口径沿用任务创建人语义，取不到按 system）。
     */
    private String resolveBatchOperator(DocumentImportBatch batch) {
        return StringUtils.hasText(batch.getCreatedBy()) ? batch.getCreatedBy() : "system";
    }

    // ==================== 提交侧私有方法 ====================

    /**
     * 事务提交后触发异步批处理；无活动事务时直接投递（对齐 ProductService.triggerAsyncProcess 模式）。
     */
    private void triggerAsyncImport(String taskId, String batchId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    asyncTaskProcessor.processDocumentImport(taskId, batchId);
                }
            });
        } else {
            asyncTaskProcessor.processDocumentImport(taskId, batchId);
        }
    }

    /**
     * 注册事务回滚清理：若当前事务最终回滚，则删除已写入存储的原始 PDF，避免孤儿文件。
     */
    private void registerStorageRollbackCleanup(List<String> objectKeys) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || objectKeys.isEmpty()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) {
                    return;
                }
                for (String objectKey : objectKeys) {
                    try {
                        storageService.delete(objectKey);
                    } catch (IOException e) {
                        log.warn("事务回滚后清理文件失败: {}", objectKey, e);
                    }
                }
            }
        });
    }

    // ==================== 页面检测（块内批量 + 单页重试，逻辑与同步版一致） ====================

    /**
     * 按 pageType 判断产品页（不要求 products 非空，容忍 AI 漏检 bbox 的情况）。
     */
    private static boolean isProductPageType(DocumentProductRegion region) {
        return "product".equalsIgnoreCase(region.getPageType());
    }

    /**
     * 单个产品的图片来源：嵌入原图（embeddedImage 非空）或页面 bbox 裁剪（bbox 非空）。
     * nearbyText 为页面级检测时提取的产品旁说明文字，随录入任务传递，作为裁剪图 OCR 的补充。
     * siblingCores 为同页其他产品的核心框，裁切边外延恢复时用于限制外延不侵占兄弟产品区域。
     */
    private record ProductSource(String estimatedCategory, ProductBoundingBox bbox, BufferedImage embeddedImage,
                                 OcrResult nearbyText, List<ProductBoundingBox> siblingCores) {
    }

    /**
     * 页级处理结果（page_results JSONB 明细元素）。
     */
    record PageResult(Integer pageIndex, String pageType, int productCount) {
    }

    /**
     * 批次处理累计器：跨页块传递计数与明细，块尾由 {@link #flushProgress} 落库。
     */
    static final class ImportAccumulator {
        int processedPages;
        int productPages;
        int totalProducts;
        int successCount;
        int failCount;
        int skipCount;
        final List<DocumentImportFailure> failures = new ArrayList<>();
        final List<PageResult> pageResults = new ArrayList<>();
        final List<String> taskIds = new ArrayList<>();
        final List<String> rspuIds = new ArrayList<>();
    }

    /**
     * 构建一页的产品来源列表。
     *
     * <p>决策规则：页面含大面积嵌入图且数量不少于 AI 检出的有效产品时，
     * 直接使用嵌入原图（零渲染损失、天然完整）；否则走 AI bbox 裁剪路径
     * （bbox 先经 {@link ProductBoxRefiner} 清洗去重）。</p>
     *
     * <p>场景图（效果图）甄别：AI 标记 imageKind=scene 且无产品说明文字（nearbyText 为空）
     * 的框视为场景中的点缀产品，直接剔除；场景中完整可见且带说明文字的产品保留，走 bbox
     * 裁剪录入。嵌入图经 {@link ImageBackgroundAnalyzer} 边框带规则判别，疑似场景图不直取
     * （场景照片不是干净的单品主图），由 AI bbox 路径按上述规则处理。</p>
     */
    private List<ProductSource> buildProductSources(DocumentProductRegion region,
                                                    List<BufferedImage> embeddedImages,
                                                    int pageWidth, int pageHeight) {
        List<ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct>> refined = new ArrayList<>(
            ProductBoxRefiner.refineAll(region.getProducts(),
                DocumentProductRegion.PageProduct::getBbox, pageWidth, pageHeight));
        // 剔除"场景中且无文字说明"的产品框；场景中完整且带说明文字的产品保留录入
        int sceneBoxCount = 0;
        for (int i = refined.size() - 1; i >= 0; i--) {
            DocumentProductRegion.PageProduct product = refined.get(i).source();
            if (isSceneImage(product) && !hasProductText(product.getNearbyText())) {
                refined.remove(i);
                sceneBoxCount++;
            }
        }
        if (sceneBoxCount > 0) {
            log.info("剔除场景中无说明文字的产品框 {} 个，pageIndex={}", sceneBoxCount, region.getPageIndex());
        }

        // 疑似场景图的嵌入图不直取（场景照片不是干净的单品主图）：
        // 场景中完整且带说明文字的产品由上方保留的 AI bbox 框裁剪录入
        List<BufferedImage> standaloneEmbedded = null;
        if (embeddedImages != null && !embeddedImages.isEmpty()) {
            standaloneEmbedded = new ArrayList<>(embeddedImages.size());
            for (BufferedImage embedded : embeddedImages) {
                if (ImageBackgroundAnalyzer.looksLikeSceneImage(embedded)) {
                    log.info("疑似场景嵌入图不直取，交 AI bbox 路径处理，pageIndex={}", region.getPageIndex());
                } else {
                    standaloneEmbedded.add(embedded);
                }
            }
        }

        if (standaloneEmbedded != null && !standaloneEmbedded.isEmpty()
            && standaloneEmbedded.size() >= refined.size()) {
            List<ProductSource> sources = new ArrayList<>(standaloneEmbedded.size());
            for (int i = 0; i < standaloneEmbedded.size(); i++) {
                // 品类与文字按检出顺序映射，嵌入图多于 AI 产品时映射不到则交给 hint 兜底
                String category = i < refined.size() ? refined.get(i).source().getEstimatedCategory() : null;
                OcrResult nearbyText = i < refined.size() ? refined.get(i).source().getNearbyText() : null;
                sources.add(new ProductSource(category, null, standaloneEmbedded.get(i), nearbyText, List.of()));
            }
            return sources;
        }

        List<ProductSource> sources = new ArrayList<>(refined.size());
        for (int i = 0; i < refined.size(); i++) {
            ProductBoxRefiner.Refined<DocumentProductRegion.PageProduct> r = refined.get(i);
            // 同页其他产品的核心框（外延恢复时不侵占这些区域）
            List<ProductBoundingBox> siblings = new ArrayList<>(refined.size() - 1);
            for (int j = 0; j < refined.size(); j++) {
                if (j != i) {
                    siblings.add(refined.get(j).box());
                }
            }
            sources.add(new ProductSource(r.source().getEstimatedCategory(), r.box(), null,
                r.source().getNearbyText(), siblings));
        }
        return sources;
    }

    /**
     * AI 是否把该产品图标记为场景图（imageKind=scene）。为 null 按单品图处理（兼容旧结果）。
     */
    private static boolean isSceneImage(DocumentProductRegion.PageProduct product) {
        return "scene".equalsIgnoreCase(product.getImageKind());
    }

    /**
     * 产品旁是否提取到任何说明文字（品名/型号/尺寸/价格/材质/原文任一项非空）。
     * 场景图中的产品只有带说明文字时才值得建档——文字是"该场景产品在画册中正式售卖"的信号。
     */
    private static boolean hasProductText(OcrResult nearbyText) {
        if (nearbyText == null) {
            return false;
        }
        return isNotBlank(nearbyText.getProductName())
            || isNotBlank(nearbyText.getModelNumber())
            || isNotBlank(nearbyText.getDimensionText())
            || isNotBlank(nearbyText.getPriceText())
            || isNotBlank(nearbyText.getMaterialDescription())
            || isNotBlank(nearbyText.getRawText());
    }

    private static boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 分批检测一个页块的产品区域（输入仅为本块页位图，region.pageIndex 为块内相对序号）。
     */
    private List<DocumentProductRegion> detectProductRegions(List<BufferedImage> pageImages) {
        List<DocumentProductRegion> allRegions = new ArrayList<>(pageImages.size());
        for (int i = 0; i < pageImages.size(); i++) {
            allRegions.add(null);
        }

        int totalPages = pageImages.size();
        for (int start = 0; start < totalPages; start += detectBatchSize) {
            int end = Math.min(start + detectBatchSize, totalPages);
            List<BufferedImage> batchImages = pageImages.subList(start, end);

            try {
                List<InputStream> compressedStreams = new ArrayList<>(batchImages.size());
                for (BufferedImage image : batchImages) {
                    compressedStreams.add(compressForDetection(image));
                }
                List<DocumentProductRegion> batchRegions = visionService.detectPageRegions(compressedStreams, null);
                for (int i = 0; i < batchRegions.size(); i++) {
                    DocumentProductRegion region = batchRegions.get(i);
                    region.setPageIndex(start + i);
                    allRegions.set(start + i, region);
                }
            } catch (Exception e) {
                log.error("页面区域检测失败，pageRange={}-{}，降级为单页 unknown", start, end - 1, e);
                for (int i = start; i < end; i++) {
                    DocumentProductRegion fallback = new DocumentProductRegion();
                    fallback.setPageIndex(i);
                    fallback.setPageType("unknown");
                    allRegions.set(i, fallback);
                }
            }
        }

        retryFailedPages(pageImages, allRegions);
        return allRegions;
    }

    /**
     * 对检测失败的页逐页单独重试一次，避免整批失败或模型输出格式抖动导致产品整体丢失：
     * <ul>
     *   <li>unknown 页（批检测失败或 JSON 截断降级）；</li>
     *   <li>pageType=product 但所有产品 bbox 解析为 null 的页（模型偶发不输出位置框，
     *   实测整批 5 页集体出现，单页重试通常能恢复正常输出）。</li>
     * </ul>
     */
    private void retryFailedPages(List<BufferedImage> pageImages, List<DocumentProductRegion> allRegions) {
        for (int i = 0; i < allRegions.size(); i++) {
            DocumentProductRegion region = allRegions.get(i);
            if (region == null || !needsRetry(region)) {
                continue;
            }
            try {
                List<DocumentProductRegion> retried = visionService.detectPageRegions(
                    List.of(compressForDetection(pageImages.get(i))), null);
                if (!retried.isEmpty() && retried.get(0) != null && !needsRetry(retried.get(0))) {
                    DocumentProductRegion recovered = retried.get(0);
                    recovered.setPageIndex(i);
                    allRegions.set(i, recovered);
                    log.info("检测失败页单页重试成功，pageIndex={}，pageType={}", i, recovered.getPageType());
                } else {
                    log.warn("检测失败页单页重试仍无效，pageIndex={}", i);
                }
            } catch (Exception e) {
                log.warn("检测失败页单页重试失败，pageIndex={}", i, e);
            }
        }
    }

    /**
     * 判断该页检测结果是否需要单页重试：unknown 页，或产品页但产品 bbox 全为 null。
     */
    private static boolean needsRetry(DocumentProductRegion region) {
        if ("unknown".equals(region.getPageType())) {
            return true;
        }
        return "product".equalsIgnoreCase(region.getPageType())
            && region.getProducts() != null && !region.getProducts().isEmpty()
            && region.getProducts().stream().allMatch(p -> p.getBbox() == null);
    }

    /**
     * 将页面图压缩为适合 AI 检测的大小。
     */
    private InputStream compressForDetection(BufferedImage source) throws IOException {
        int width = source.getWidth();
        int height = source.getHeight();
        if (Math.max(width, height) <= DETECT_IMAGE_MAX_EDGE) {
            return encodeJpeg(source);
        }

        double ratio = (double) DETECT_IMAGE_MAX_EDGE / Math.max(width, height);
        int newWidth = (int) Math.round(width * ratio);
        int newHeight = (int) Math.round(height * ratio);

        Image scaled = source.getScaledInstance(newWidth, newHeight, Image.SCALE_SMOOTH);
        BufferedImage output = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = output.createGraphics();
        g.drawImage(scaled, 0, 0, null);
        g.dispose();
        return encodeJpeg(output);
    }

    private InputStream encodeJpeg(BufferedImage image) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new ByteArrayInputStream(out.toByteArray());
    }

    /**
     * 提取产品图（嵌入原图直取 或 bbox 外扩裁剪 + 白边精修）并创建录入任务。
     *
     * <p>统一使用文档导入专用的收紧策略（{@link ImageWhitespaceTrimmer.TrimOptions#document()}）：
     * 保守收紧（每边限幅 + 细腿保护 + 场景背景不收）+ 内容重裁（纯色背景下裁掉卡片底部
     * 说明文字带与边缘相邻图切片），宁可多留边也绝不切到产品。</p>
     *
     * <p>建档前先按图片字节 contentHash 查 image_assets（未软删）查重（3.1）：命中即跳过建档，
     * 在批次明细中记"已存在跳过"，防重传同一 PDF 整批重复建档。</p>
     *
     * @return 录入信息，包含 RSPU ID 和任务 ID；查重命中跳过返回 null
     */
    private EntryInfo createEntryFromSource(String batchId, BufferedImage pageImage, ProductSource source,
                                            String categoryHint, int pageIndex, ImportAccumulator acc,
                                            String entryCreatedBy)
        throws IOException {
        byte[] imageBytes;
        if (source.embeddedImage() != null) {
            // 嵌入原图：整图白边精修（去扫描边距 + 留白），不经任何渲染缩放；
            // 无 AI 框可锚定，用保守收紧、不开内容重裁（防浅色产品底部被误吃）
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(source.embeddedImage(),
                new ProductBoundingBox(0.0, 0.0, 1.0, 1.0), 0.0, CROP_PAD_RATIO, outputQuality,
                ImageWhitespaceTrimmer.TrimOptions.conservative());
        } else {
            // AI bbox：先做裁切边外延恢复（框底/侧边低估时，把框外连续的产品内容并回核心框），
            // 再按恢复后的核心框外扩裁剪；锚定核心框收紧白边 + 文字带重裁（只清核心框外）+ 留白
            ProductBoundingBox core = recoverCutEdges(pageImage, source.bbox(), source.siblingCores());
            ProductBoundingBox expanded = expandBox(core, BOX_EXPAND_RATIO);
            imageBytes = ImageWhitespaceTrimmer.cropRefineToJpeg(pageImage, expanded,
                0.0, CROP_PAD_RATIO, outputQuality,
                ImageWhitespaceTrimmer.TrimOptions.document(), core);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            throw new BusinessException("提取产品图失败");
        }

        // 图片内容查重（3.1）：同一产品图已入库（未软删）时跳过建档，防重传同一 PDF 整批重复。
        // 脱敏口径参照 1.5/2.5：异步线程无 SecurityContext，isPlatformStaff() 恒为 false，
        // 批次明细统一落中性文案（不含已有产品品名/编码）
        ImageAssets duplicate = imageAssetsMapper.selectByContentHash(ContentHashes.sha256Hex(imageBytes));
        if (duplicate != null) {
            acc.skipCount++;
            acc.failures.add(new DocumentImportFailure(pageIndex, buildDuplicateSkipMessage(duplicate)));
            log.info("产品图已存在（contentHash 命中），跳过建档，batchId={}，pageIndex={}，命中 imageId={}",
                batchId, pageIndex, duplicate.getImageId());
            return null;
        }

        String effectiveCategory = resolveCategory(source.estimatedCategory(), categoryHint);
        String filename = batchId + "_page_product.jpg";
        Map<String, Object> entryResult;
        try (InputStream in = new ByteArrayInputStream(imageBytes)) {
            entryResult = productService.createEntryFromStream(in, filename, imageBytes.length, effectiveCategory,
                source.nearbyText(), entryCreatedBy);
        }

        Object rspuId = entryResult.get("rspuId");
        Object taskId = entryResult.get("taskId");
        if (rspuId != null && taskId != null) {
            return new EntryInfo(rspuId.toString(), taskId.toString());
        }
        return null;
    }

    /**
     * 查重命中跳过建档的明细文案（脱敏口径对齐 ProductService.buildDuplicateEntryMessage：
     * 非平台员工不含已有产品品名/编码；批处理在异步线程执行时统一为中性文案）。
     */
    private String buildDuplicateSkipMessage(ImageAssets duplicate) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            return "产品图已存在，跳过建档（避免重复录入）";
        }
        return "产品图已存在（对应产品：" + describeDuplicateProduct(duplicate) + "），跳过建档";
    }

    /**
     * 描述图片查重命中的已有产品（品名 + 业务编码/RSPU ID），用于重复导入提示。
     */
    private String describeDuplicateProduct(ImageAssets duplicate) {
        if (duplicate.getRspuId() == null) {
            return "（图片 " + duplicate.getImageId() + "）";
        }
        RspuMaster rspu = rspuMapper.selectById(duplicate.getRspuId());
        if (rspu == null) {
            return "RSPU " + duplicate.getRspuId();
        }
        String name = StringUtils.hasText(rspu.getProductName()) ? rspu.getProductName() : rspu.getRspuId();
        String code = StringUtils.hasText(rspu.getRspuCode()) ? "（" + rspu.getRspuCode() + "）" : "";
        return "「" + name + "」" + code;
    }

    // ==================== 结果视图解析 ====================

    private int valueOrZero(Integer value) {
        return value != null ? value : 0;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("批次明细 JSON 序列化失败", e);
            return null;
        }
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("批次 ID 列表 JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<DocumentImportFailure> readFailures(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                objectMapper.getTypeFactory().constructCollectionType(List.class, DocumentImportFailure.class));
        } catch (Exception e) {
            log.warn("批次失败明细 JSON 解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    // ==================== bbox 外扩与裁切边外延恢复（与同步版一致） ====================

    /**
     * 按 bbox 自身宽高的比例外扩（相对坐标，结果钳制在 [0,1] 内）。
     */
    static ProductBoundingBox expandBox(ProductBoundingBox box, double ratio) {
        double dx = box.getWidth() * ratio;
        double dy = box.getHeight() * ratio;
        double x = Math.max(0.0, box.getX() - dx);
        double y = Math.max(0.0, box.getY() - dy);
        double width = Math.min(1.0 - x, box.getWidth() + 2 * dx);
        double height = Math.min(1.0 - y, box.getHeight() + 2 * dy);
        return new ProductBoundingBox(x, y, width, height);
    }

    /**
     * 外延恢复最大比例（相对核心框该方向尺寸）。
     */
    private static final double RECOVER_MAX_RATIO = 0.25;

    /**
     * 外延扫描时判定内容终止的连续空白行/列数。
     */
    private static final int RECOVER_BLANK_RUN = 3;

    /**
     * 裁切边外延恢复：AI 框低估（框边切进产品）时，把框外连续的产品内容并回核心框。
     *
     * <p>白色/浅色产品在白底上最容易被框小（田吉/条纹布艺/像素/波西米亚实测案例）。
     * 对四个方向分别处理：核心框边缘行/列含产品内容（色差或织物纹理，即有"被切断"
     * 信号）时向外逐行/列扫描，内容连续就延伸；遇到 {@value #RECOVER_BLANK_RUN} 个连续
     * 空白行/列（说明文字前的隔断）、兄弟产品框边界、页边或
     * {@value #RECOVER_MAX_RATIO} 上限即停止。恢复后的区域并入核心框——锚定机制会自动
     * 保护它不被收紧/文字带重裁误吃，隔断外的说明文字仍会被正常清掉。</p>
     *
     * @param page     渲染页面图
     * @param core     AI 原始框（相对坐标）
     * @param siblings 同页其他产品的核心框
     * @return 恢复后的核心框；无切断信号时原样返回
     */
    static ProductBoundingBox recoverCutEdges(BufferedImage page, ProductBoundingBox core,
                                              List<ProductBoundingBox> siblings) {
        if (page == null || core == null || !core.isValid()) {
            return core;
        }
        int bgLum = pageBackgroundLuminance(page);
        double x0 = core.getX();
        double y0 = core.getY();
        double x1 = x0 + core.getWidth();
        double y1 = y0 + core.getHeight();
        List<ProductBoundingBox> safeSiblings = siblings != null ? siblings : List.of();

        double newY1 = recoverEdge(page, safeSiblings, bgLum, true, 1, x0, x1, y1, core.getHeight());
        double newX1 = recoverEdge(page, safeSiblings, bgLum, false, 1, y0, y1, x1, core.getWidth());
        double newY0 = recoverEdge(page, safeSiblings, bgLum, true, -1, x0, x1, y0, core.getHeight());
        double newX0 = recoverEdge(page, safeSiblings, bgLum, false, -1, y0, y1, x0, core.getWidth());

        if (newX0 == x0 && newY0 == y0 && newX1 == x1 && newY1 == y1) {
            return core;
        }
        double nx0 = Math.max(0.0, Math.min(newX0, newX1));
        double ny0 = Math.max(0.0, Math.min(newY0, newY1));
        double nx1 = Math.min(1.0, Math.max(newX0, newX1));
        double ny1 = Math.min(1.0, Math.max(newY0, newY1));
        log.info("裁切边外延恢复：底边 {}→{}，右边 {}→{}，顶边 {}→{}，左边 {}→{}",
            y1, newY1, x1, newX1, y0, newY0, x0, newX0);
        return new ProductBoundingBox(nx0, ny0, nx1 - nx0, ny1 - ny0);
    }

    /**
     * 单方向外延恢复，返回该方向新的边缘坐标（相对值）。
     *
     * @param isRow        true=垂直方向（上/下，扫行），false=水平方向（左/右，扫列）
     * @param sign         +1=下/右边缘，-1=上/左边缘
     * @param rangeFrom    扫描跨度的起始（相对坐标：垂直方向为 x0，水平方向为 y0）
     * @param rangeTo      扫描跨度的结束（相对坐标）
     * @param edgeRel      核心框该方向的边缘（相对坐标）
     * @param coreSizeRel  核心框该方向的尺寸（相对坐标，用于外延上限）
     */
    private static double recoverEdge(BufferedImage page, List<ProductBoundingBox> siblings, int bgLum,
                                      boolean isRow, int sign, double rangeFrom, double rangeTo,
                                      double edgeRel, double coreSizeRel) {
        int pageW = page.getWidth();
        int pageH = page.getHeight();
        int span = isRow ? pageH : pageW;
        int cross = isRow ? pageW : pageH;
        int edgePx = sign > 0 ? (int) Math.round(edgeRel * span) - 1 : (int) Math.round(edgeRel * span);
        if (edgePx < 0 || edgePx >= span) {
            return edgeRel;
        }
        int fromPx = (int) Math.round(rangeFrom * cross);
        int toPx = Math.min(cross, (int) Math.round(rangeTo * cross));

        // 切断信号：边缘行/列本身是产品内容；边缘是背景说明框外没有产品延续，不外延
        if (!lineHasContent(page, isRow, edgePx, fromPx, toPx, bgLum)) {
            return edgeRel;
        }

        // 外延上限：页边 → 兄弟框边界 → 核心框尺寸比例上限，逐层收紧
        int limit = sign > 0 ? span - 1 : 0;
        for (ProductBoundingBox sibling : siblings) {
            double otherFrom = isRow ? sibling.getX() : sibling.getY();
            double otherTo = otherFrom + (isRow ? sibling.getWidth() : sibling.getHeight());
            if (!rangesOverlap(rangeFrom, rangeTo, otherFrom, otherTo)) {
                continue;
            }
            double siblingEdgeRel = sign > 0 ? (isRow ? sibling.getY() : sibling.getX())
                : (isRow ? sibling.getY() + sibling.getHeight() : sibling.getX() + sibling.getWidth());
            int siblingEdgePx = (int) Math.round(siblingEdgeRel * span) + (sign > 0 ? -2 : 2);
            if (sign > 0 && siblingEdgePx > edgePx) {
                limit = Math.min(limit, siblingEdgePx);
            }
            if (sign < 0 && siblingEdgePx < edgePx) {
                limit = Math.max(limit, siblingEdgePx);
            }
        }
        int cap = (int) Math.round(edgeRel * span + sign * coreSizeRel * span * RECOVER_MAX_RATIO);
        limit = sign > 0 ? Math.min(limit, cap) : Math.max(limit, cap);

        int blankRun = 0;
        int contentEnd = edgePx;
        for (int p = edgePx + sign; sign > 0 ? p <= limit : p >= limit; p += sign) {
            if (lineHasContent(page, isRow, p, fromPx, toPx, bgLum)) {
                contentEnd = p;
                blankRun = 0;
            } else if (++blankRun >= RECOVER_BLANK_RUN) {
                break;
            }
        }
        if (contentEnd == edgePx) {
            return edgeRel;
        }
        return (double) (contentEnd + (sign > 0 ? 1 : 0)) / span;
    }

    /**
     * 行/列是否含产品内容：与背景亮度差达标的像素占比 ≥30%（普通产品/阴影），
     * 或亮度标准差 ≥5（浅色织物的纹理/明暗起伏，白底上几乎纯白的平滑区域不达标）。
     */
    private static boolean lineHasContent(BufferedImage page, boolean isRow, int index,
                                          int fromPx, int toPx, int bgLum) {
        int n = toPx - fromPx;
        if (n <= 0 || index < 0 || index >= (isRow ? page.getHeight() : page.getWidth())) {
            return false;
        }
        int step = Math.max(1, n / 400);
        int count = 0;
        int diffCount = 0;
        double sum = 0;
        double sumSq = 0;
        for (int i = fromPx; i < toPx; i += step) {
            int lum = luminance(isRow ? page.getRGB(i, index) : page.getRGB(index, i));
            count++;
            sum += lum;
            sumSq += (double) lum * lum;
            if (Math.abs(lum - bgLum) > 12) {
                diffCount++;
            }
        }
        double mean = sum / count;
        double stddev = Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
        return (double) diffCount / count >= 0.3 || stddev >= 5.0;
    }

    private static boolean rangesOverlap(double a0, double a1, double b0, double b1) {
        return a0 < b1 && b0 < a1;
    }

    /**
     * 页面背景亮度：四角 5×5 采样亮度的中位数。
     */
    private static int pageBackgroundLuminance(BufferedImage page) {
        int width = page.getWidth();
        int height = page.getHeight();
        int sample = Math.min(5, Math.min(width, height));
        int[][] corners = {{0, 0}, {width - sample, 0}, {0, height - sample}, {width - sample, height - sample}};
        int[] means = new int[4];
        for (int i = 0; i < corners.length; i++) {
            long sum = 0;
            for (int dy = 0; dy < sample; dy++) {
                for (int dx = 0; dx < sample; dx++) {
                    sum += luminance(page.getRGB(corners[i][0] + dx, corners[i][1] + dy));
                }
            }
            means[i] = (int) (sum / (sample * sample));
        }
        java.util.Arrays.sort(means);
        return (means[1] + means[2]) / 2;
    }

    /**
     * 像素亮度（Rec.601）。
     */
    private static int luminance(int rgb) {
        return (299 * ((rgb >> 16) & 0xFF) + 587 * ((rgb >> 8) & 0xFF) + 114 * (rgb & 0xFF)) / 1000;
    }

    private record EntryInfo(String rspuId, String taskId) {
    }

    /**
     * 解析最终品类码：优先使用 AI 检测出的品类，未检测出时使用用户提示，最后兜底 FS。
     */
    private String resolveCategory(String detectedCategory, String categoryHint) {
        if (detectedCategory != null && !detectedCategory.isBlank()) {
            return detectedCategory.trim().toUpperCase();
        }
        if (categoryHint != null && !categoryHint.isBlank()) {
            return categoryHint.trim().toUpperCase();
        }
        return "FS";
    }
}
