package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.StyleMatchResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.entity.ImageAssets;
import com.rsdp.service.EmbeddingService.ImageEmbedding;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.service.storage.StorageService;
import com.rsdp.service.vector.ProductVectorStore;
import com.rsdp.service.vector.VectorHit;
import com.rsdp.service.vector.VectorStaleImageException;
import com.rsdp.util.OcrPostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import com.rsdp.util.CategoryPaths;
import com.rsdp.util.IdGenerator;

/**
 * 异步任务处理器，负责在后台执行 AI 识别等耗时操作。
 *
 * <p>本类不再声明方法级事务；所有外部 HTTP 调用（AI 视觉识别、图片 Embedding）
 * 均在事务外执行，数据库写入通过 {@link AiRecognitionPersistenceService} 的独立短事务完成。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskProcessor {

    private final AsyncTaskMapper asyncTaskMapper;
    private final RspuMapper rspuMapper;
    private final VisionService visionService;
    private final EmbeddingService embeddingService;
    private final ProductVectorStore productVectorStore;
    private final ImageAssetsMapper imageAssetsMapper;
    private final StorageService storageService;
    private final AiRecognitionPersistenceService persistenceService;
    private final AuditLogService auditLogService;
    private final StyleMatchingService styleMatchingService;
    private final RspuVariantService rspuVariantService;
    private final ProductSubjectCropService subjectCropService;
    private final FloorPlanAnalysisMapper floorPlanAnalysisMapper;
    /**
     * 户型图分析服务（延迟解析，打破 FloorPlanService ↔ AsyncTaskProcessor 循环依赖）。
     */
    private final ObjectProvider<FloorPlanService> floorPlanServiceProvider;
    private final ObjectMapper objectMapper;
    /** 向量重建服务（Worker D 提供）：重编码图片向量并写入 pgvector。 */
    private final VectorRebuildService vectorRebuildService;

    @Value("${rsdp.ai.model}")
    private String aiModel;

    @Value("${rsdp.ai.max-image-size:20971520}")
    private long maxImageSize = 20 * 1024 * 1024;

    /** 同款检测：向量相似度阈值（0~1），超过则标记"存疑-疑似同款" */
    @Value("${rsdp.dedup.similar-threshold:0.95}")
    private double duplicateSimilarThreshold;

    /**
     * 异步处理产品录入任务：AI 视觉识别并更新相关记录。
     *
     * @param taskId    任务 ID
     * @param rspuId    RSPU ID
     * @param imageId   图片 ID
     * @param objectKey 存储对象键
     */
    @Async("taskExecutor")
    public void processProductEntry(String taskId, String rspuId, String imageId, String objectKey) {
        log.info("开始异步处理产品录入任务，taskId={}", taskId);
        // 原子认领任务：仅 pending 状态可置为 processing，防止多执行器并发重复处理同一任务
        if (asyncTaskMapper.claimPendingTask(taskId) == 0) {
            log.warn("任务已被认领或不处于 pending 状态，跳过处理，taskId={}", taskId);
            return;
        }

        // 审计操作人：异步线程无 SecurityContext（ThreadLocal 为空会落成 anonymous），
        // 显式取任务创建人（async_task.created_by）透传给所有写库审计；取不到按 system
        String operator = resolveTaskOperator(taskId);

        String recognitionId = IdGenerator.recognitionId();
        String modelName = aiModel;
        int processingTime = 0;

        RspuMaster rspu = rspuMapper.selectById(rspuId);
        String categoryCode = rspu != null ? rspu.getCategoryCode() : null;

        byte[] imageBytes;
        try (InputStream imageStream = storageService.get(objectKey)) {
            imageBytes = imageStream.readAllBytes();
            if (imageBytes.length > maxImageSize) {
                String msg = "图片大小超过限制：" + imageBytes.length + " 字节（最大允许 " + maxImageSize + " 字节）";
                log.error("{}，taskId={}", msg, taskId);
                safeSaveFailure(taskId, rspuId, imageId, recognitionId, modelName, msg, operator);
                safeUpdateTaskStatus(taskId, "failed", 100, null, msg);
                return;
            }
        } catch (Exception e) {
            log.error("读取图片失败，taskId={}", taskId, e);
            safeSaveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage(), operator);
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
            return;
        }

        // 读取图片当前内容版本（向量防旧写保护：编码期间内容被更新则丢弃向量，
        // 由重建任务重新编码）；行不存在或版本为空按 1 处理
        long currentRevision = 1L;
        LocalDateTime imageDeletedAt = null;
        try {
            ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
            if (imageAsset != null) {
                if (imageAsset.getContentRevision() != null) {
                    currentRevision = imageAsset.getContentRevision();
                }
                imageDeletedAt = imageAsset.getDeletedAt();
            }
        } catch (Exception e) {
            log.warn("读取图片内容版本失败，按 1 处理，imageId={}", imageId, e);
        }
        if (imageDeletedAt != null) {
            log.warn("图片已删除仍继续识别流程，向量写入将被丢弃，imageId={}", imageId);
        }

        // 主图智能裁剪：AI 识别产品主体并替换主图，识别失败时回退原图不影响流程。
        // 命中时向量计算基于裁剪图（保证以图搜图语义一致）；
        // AI 识别走双图模式（裁剪图看形态 + 原图提取 OCR 文字），避免裁剪裁掉品名/尺寸文字。
        // 文档导入（PDF/PPT）的图片已经过页面级主体裁剪，跳过二次检测；
        // Excel 导入的图片为表格内嵌/链接直接提取的成品图，直接使用原图，不做 AI 裁剪。
        byte[] originalImageBytes = imageBytes;
        boolean subjectCropped = false;
        if (!isDocumentImportTask(taskId) && !isExcelImportTask(taskId)) {
            Optional<byte[]> croppedImage = subjectCropService.cropAndReplacePrimary(
                imageBytes, rspuId, null, imageId, objectKey);
            if (croppedImage.isPresent()) {
                imageBytes = croppedImage.get();
                subjectCropped = true;
            }
        }

        // 品类自动判定：录入时用户未选品类（系统兜底 FS）时，用原图（含品名/规格文字版面）
        // 轻量判定品类并纠正——后续六维 schema、风格匹配、业务编码都按正确品类执行。
        // 判定失败不影响主流程，沿用兜底品类。
        if (isCategoryAutoDetectTask(taskId)) {
            try {
                String detected = visionService.classifyCategory(new ByteArrayInputStream(originalImageBytes));
                if (StringUtils.hasText(detected) && !detected.equalsIgnoreCase(categoryCode) && rspu != null) {
                    String previous = categoryCode;
                    // 审计旧快照（P0-2）：品类纠正此前绕过审计日志
                    RspuMaster oldSnapshot = new RspuMaster();
                    oldSnapshot.setRspuId(rspu.getRspuId());
                    oldSnapshot.setCategoryCode(rspu.getCategoryCode());
                    oldSnapshot.setCategoryPath(rspu.getCategoryPath());
                    rspu.setCategoryCode(detected);
                    rspu.setCategoryPath(CategoryPaths.resolve(detected));
                    rspu.setUpdatedAt(LocalDateTime.now());
                    rspuMapper.updateById(rspu);
                    auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, operator);
                    categoryCode = detected;
                    log.info("AI 品类判定纠正品类：{} → {}，rspuId={}", previous, detected, rspuId);
                }
            } catch (Exception e) {
                log.warn("品类自动判定失败，沿用兜底品类，taskId={}", taskId, e);
            }
        }

        AiLabels labels;
        // 识别成功结果是否已提交：已提交后，后续非关键步骤（默认变体/向量/结果序列化）失败
        // 只能降级为 partial_success，绝不能再走 saveFailure（recognitionId 主键冲突 + 覆盖成功状态）
        boolean successSaved = false;
        try (InputStream imageStream = new ByteArrayInputStream(imageBytes)) {
            long aiStart = System.currentTimeMillis();
            labels = subjectCropped
                ? visionService.recognizeImage(imageStream, originalImageBytes, categoryCode)
                : visionService.recognizeImage(imageStream, categoryCode);
            processingTime = (int) (System.currentTimeMillis() - aiStart);

            // 文档导入时，页面级检测提取的产品旁说明文字合并进 OCR（裁剪图不含这些文字）
            mergePageOcr(labels, extractPageOcr(taskId));

            // AI 标签后处理：清洗 OCR 字段，规范化尺寸，OCR 材质兜底
            postProcessLabels(labels);

            // 风格数据库校验：基于 style_matching_formula 计算风格匹配得分
            StyleMatchResult styleMatch = styleMatchingService.match(labels, rspuId, categoryCode);
            if (styleMatch != null) {
                labels.setConfidence(styleMatch.getConfidence());
                log.info("风格匹配评分完成，rspuId={}，style={}，score={}，confidence={}",
                    rspuId, styleMatch.getStyleCode(), styleMatch.getOverallScore(), styleMatch.getConfidence());
            }

            updateTaskStatus(taskId, "processing", 60, null, null);

            ImageEmbedding imageEmbedding = embedImageSafely(rspuId, imageBytes);
            float[] embedding = imageEmbedding != null ? imageEmbedding.vector() : null;

            String productName = persistenceService.saveSuccess(taskId, rspuId, imageId, recognitionId, modelName,
                labels, processingTime, operator);
            successSaved = true;

            // 同款检测（写入向量前召回比对）：相似度超阈值时把新品标记"存疑-疑似同款"，
            // 由人工裁决保留或删除——不硬拦截（同款不同工厂是合法场景）
            if (embedding != null) {
                try {
                    flagDuplicateSuspect(rspuId, embedding, operator);
                } catch (Exception e) {
                    log.warn("同款检测失败，跳过，rspuId={}", rspuId, e);
                }
            }

            // 以下为非关键步骤，失败仅降级为 partial_success，不影响已提交的识别结果
            String degradeError = null;

            // AI 识别成功后，若该 RSPU 尚无变体，自动创建默认变体，便于后续批量绑定工厂报价
            try {
                rspuVariantService.initializeDefaultVariant(rspuId, labels);
            } catch (Exception e) {
                log.error("创建默认变体失败，rspuId={}", rspuId, e);
                degradeError = "AI 识别完成，但创建默认变体失败";
            }

            boolean vectorPersisted = false;
            if (imageEmbedding != null) {
                // 失败时返回降级文案（区分"存储故障"与"图片已更新被丢弃"），成功返回 null
                String vectorError = persistVector(imageId, currentRevision, imageEmbedding);
                vectorPersisted = vectorError == null;
                if (vectorError != null) {
                    degradeError = vectorError;
                }
            } else {
                degradeError = "AI 识别完成，但生成图片向量失败，以图搜图功能可能不可用";
            }

            String finalStatus = (vectorPersisted && degradeError == null) ? "done" : "partial_success";
            String resultData = null;
            try {
                // resultData 供录入中心展示：AI 原始标签 + 最终生效的产品名称（OCR 品名或品类回退名）
                com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.valueToTree(labels);
                resultNode.put("productName", productName);
                resultData = objectMapper.writeValueAsString(resultNode);
            } catch (Exception e) {
                log.error("序列化任务结果失败，taskId={}", taskId, e);
                finalStatus = "partial_success";
                degradeError = degradeError != null ? degradeError : "AI 识别完成，但结果序列化失败";
            }
            updateTaskStatus(taskId, finalStatus, 100, resultData, degradeError);
            log.info("产品录入异步任务完成，taskId={}，status={}", taskId, finalStatus);
        } catch (Exception e) {
            log.error("AI 识别失败，taskId={}", taskId, e);
            if (successSaved) {
                // 识别结果已提交：仅降级任务状态，不重复写 failed 识别记录、不覆盖 RSPU 状态
                safeUpdateTaskStatus(taskId, "partial_success", 100, null,
                    "AI 识别完成，但后续步骤失败: " + e.getMessage());
            } else {
                safeSaveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage(), operator);
                safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
            }
        }
    }

    /**
     * 异步处理户型图分析任务：AI 识别空间划分与尺寸标注，落 floor_plan_room 明细。
     *
     * <p>照 {@link #processProductEntry} 模式：claimPendingTask 原子认领；
     * AI 调用在事务外执行，DB 写入直接走 Mapper 短操作。尺寸三级提取与空间明细落库
     * 收敛在 {@link FloorPlanService#buildRooms}（OCR 标注解析 high → 比例尺换算
     * scale_calc mid（P1，需可解析的 像素↔毫米 关系）→ AI 估算 low → 留空待人工校正）。</p>
     *
     * @param taskId     任务 ID
     * @param analysisId 户型图分析批次 ID
     * @param objectKey  户型原图存储对象键
     * @param hint       用户补充说明，可空
     */
    @Async("taskExecutor")
    public void processFloorPlanAnalysis(String taskId, String analysisId, String objectKey, String hint) {
        log.info("开始异步处理户型图分析任务，taskId={}，analysisId={}", taskId, analysisId);
        // 原子认领任务：仅 pending 状态可置为 processing，防止多执行器并发重复处理同一任务
        if (asyncTaskMapper.claimPendingTask(taskId) == 0) {
            log.warn("任务已被认领或不处于 pending 状态，跳过处理，taskId={}", taskId);
            return;
        }

        safeUpdateAnalysis(analysisId, FloorPlanService.STATUS_ANALYZING, null, null);

        byte[] imageBytes;
        try (InputStream imageStream = storageService.get(objectKey)) {
            imageBytes = imageStream.readAllBytes();
        } catch (Exception e) {
            log.error("读取户型图失败，taskId={}，analysisId={}", taskId, analysisId, e);
            safeUpdateAnalysis(analysisId, FloorPlanService.STATUS_FAILED, null, "读取户型图失败: " + e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
            return;
        }

        try {
            FloorPlanDetectResult detected = visionService.detectFloorPlanRooms(imageBytes, hint);
            updateTaskStatus(taskId, "processing", 60, null, null);

            // 尺寸三级提取 + 空间明细落库：v3.0 §4.4 收敛在 FloorPlanService 内实现（唯一出口）
            floorPlanServiceProvider.getObject().buildRooms(analysisId, detected);

            String rawResult = objectMapper.writeValueAsString(detected);
            safeUpdateAnalysis(analysisId, FloorPlanService.STATUS_AWAITING_CONFIRM, rawResult, null);
            updateTaskStatus(taskId, "done", 100, null, null);
            log.info("户型图分析异步任务完成，taskId={}，analysisId={}，识别空间数={}",
                taskId, analysisId, detected.getRooms().size());
        } catch (Exception e) {
            log.error("户型图空间识别失败，taskId={}，analysisId={}", taskId, analysisId, e);
            safeUpdateAnalysis(analysisId, FloorPlanService.STATUS_FAILED, null, e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
        }
    }

    /**
     * 更新户型图分析状态；自身失败不中断任务状态更新。
     */
    private void safeUpdateAnalysis(String analysisId, String status, String rawResult, String errorMessage) {
        try {
            FloorPlanAnalysis analysis = floorPlanAnalysisMapper.selectById(analysisId);
            if (analysis == null) {
                log.warn("户型图分析记录不存在，analysisId={}", analysisId);
                return;
            }
            analysis.setStatus(status);
            if (rawResult != null) {
                analysis.setRawResult(rawResult);
            }
            analysis.setErrorMessage(errorMessage);
            analysis.setUpdatedAt(LocalDateTime.now());
            floorPlanAnalysisMapper.updateById(analysis);
        } catch (Exception ex) {
            log.error("更新户型图分析状态异常，analysisId={}", analysisId, ex);
        }
    }

    /**
     * saveFailure 自身的持久化失败（如主键冲突、DB 故障）不能中断后续任务状态更新。
     */
    private void safeSaveFailure(String taskId, String rspuId, String imageId,
                                 String recognitionId, String modelName, String errorMessage, String operator) {
        try {
            persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, errorMessage, operator);
        } catch (Exception ex) {
            log.error("保存识别失败记录异常，taskId={}", taskId, ex);
        }
    }

    /**
     * 解析审计操作人：异步线程（rsdp-async-*）无 SecurityContext，
     * 取任务创建人（async_task.created_by）作为审计操作人；取不到按 "system"。
     *
     * @param taskId 任务 ID
     * @return 审计操作人
     */
    private String resolveTaskOperator(String taskId) {
        try {
            AsyncTask task = asyncTaskMapper.selectById(taskId);
            if (task != null && StringUtils.hasText(task.getCreatedBy())) {
                return task.getCreatedBy();
            }
        } catch (Exception e) {
            log.warn("读取任务创建人失败，审计操作人按 system 处理，taskId={}", taskId, e);
        }
        return "system";
    }

    /**
     * 从任务 input_data 中提取页面级 OCR 文字（文档导入时写入，图片录入无此字段）。
     *
     * @param taskId 任务 ID
     * @return 页面级 OCR 结果，无则返回 null
     */
    /**
     * 判断任务是否来自文档导入（PDF/PPT）。
     * 文档导入的图片已经过页面级主体裁剪，无需再做单图主体检测。
     */
    private boolean isDocumentImportTask(String taskId) {
        try {
            AsyncTask task = asyncTaskMapper.selectById(taskId);
            if (task == null || !StringUtils.hasText(task.getInputData())) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getInputData());
            com.fasterxml.jackson.databind.JsonNode source = root.get("source");
            return source != null && "document_import".equals(source.asText());
        } catch (Exception e) {
            log.warn("解析任务 source 失败，按普通录入处理，taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 判断任务是否来自 Excel 导入。
     * Excel 导入的图片为表格内嵌/链接直接提取的成品图，直接使用原图，无需 AI 主体裁剪。
     */
    private boolean isExcelImportTask(String taskId) {
        try {
            AsyncTask task = asyncTaskMapper.selectById(taskId);
            if (task == null || !StringUtils.hasText(task.getInputData())) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getInputData());
            com.fasterxml.jackson.databind.JsonNode source = root.get("source");
            return source != null && "excel_import".equals(source.asText());
        } catch (Exception e) {
            log.warn("解析任务 source 失败，按普通录入处理，taskId={}", taskId, e);
            return false;
        }
    }

    /**
     * 判断任务是否需要 AI 自动判定品类（录入时用户未选品类，系统在 input_data 打了标记）。
     */
    private boolean isCategoryAutoDetectTask(String taskId) {
        try {
            AsyncTask task = asyncTaskMapper.selectById(taskId);
            if (task == null || !StringUtils.hasText(task.getInputData())) {
                return false;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getInputData());
            com.fasterxml.jackson.databind.JsonNode flag = root.get("categoryAutoDetect");
            return flag != null && flag.asBoolean(false);
        } catch (Exception e) {
            log.warn("解析任务 categoryAutoDetect 失败，跳过品类判定，taskId={}", taskId, e);
            return false;
        }
    }

    private OcrResult extractPageOcr(String taskId) {
        try {
            AsyncTask task = asyncTaskMapper.selectById(taskId);
            if (task == null || !StringUtils.hasText(task.getInputData())) {
                return null;
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(task.getInputData());
            com.fasterxml.jackson.databind.JsonNode pageOcrNode = root.get("pageOcr");
            if (pageOcrNode == null || pageOcrNode.isNull()) {
                return null;
            }
            return objectMapper.treeToValue(pageOcrNode, OcrResult.class);
        } catch (Exception e) {
            log.warn("解析任务 pageOcr 失败，忽略页面文字，taskId={}", taskId, e);
            return null;
        }
    }

    /**
     * 将页面级检测提取的产品旁说明文字合并进裁剪图 OCR 结果。
     *
     * <p>文档（PDF）导入时，品名/型号/尺寸/价格等文字排在产品图旁边，裁剪图 OCR 看不到；
     * 页面级文字逐字段补缺（裁剪图 OCR 已识别出的字段不覆盖），rawText 拼接在前面。
     * 例外：品名以页面级文字为准（覆盖）——裁剪图不含文字，图像 OCR 的品名是模型猜测。</p>
     *
     * @param labels  AI 识别标签（原地修改）
     * @param pageOcr 页面级 OCR 文字，可为 null
     */
    private void mergePageOcr(AiLabels labels, OcrResult pageOcr) {
        if (labels == null || pageOcr == null) {
            return;
        }
        OcrResult ocr = labels.getOcr();
        if (ocr == null) {
            labels.setOcr(pageOcr);
            return;
        }
        // 品名以页面级文字为准（覆盖而非补缺）：裁剪图刻意不含说明文字，
        // 图像 OCR 的品名是模型看图猜测的描述性命名（如「三人位布艺沙发」甚至「未知」），
        // 页面级品名来自真实说明文字，必须优先（实测「云沙发」被猜成「三人位布艺沙发」）
        if (StringUtils.hasText(pageOcr.getProductName())) {
            ocr.setProductName(pageOcr.getProductName());
        }
        if (!StringUtils.hasText(ocr.getModelNumber())) {
            ocr.setModelNumber(pageOcr.getModelNumber());
        }
        if (!StringUtils.hasText(ocr.getBrand())) {
            ocr.setBrand(pageOcr.getBrand());
        }
        if (!StringUtils.hasText(ocr.getFactoryName())) {
            ocr.setFactoryName(pageOcr.getFactoryName());
        }
        if (!StringUtils.hasText(ocr.getDimensionText())) {
            ocr.setDimensionText(pageOcr.getDimensionText());
        }
        if (ocr.getDimensions() == null) {
            ocr.setDimensions(pageOcr.getDimensions());
        }
        if (!StringUtils.hasText(ocr.getMaterialDescription())) {
            ocr.setMaterialDescription(pageOcr.getMaterialDescription());
        }
        if (!StringUtils.hasText(ocr.getColorText())) {
            ocr.setColorText(pageOcr.getColorText());
        }
        if (!StringUtils.hasText(ocr.getPriceText())) {
            ocr.setPriceText(pageOcr.getPriceText());
        }
        if (ocr.getPrice() == null) {
            ocr.setPrice(pageOcr.getPrice());
        }
        if (!StringUtils.hasText(ocr.getCurrency())) {
            ocr.setCurrency(pageOcr.getCurrency());
        }
        if (ocr.getOtherInfo() == null || ocr.getOtherInfo().isEmpty()) {
            ocr.setOtherInfo(pageOcr.getOtherInfo());
        }
        if (StringUtils.hasText(pageOcr.getRawText())) {
            ocr.setRawText(StringUtils.hasText(ocr.getRawText())
                ? pageOcr.getRawText() + "\n" + ocr.getRawText()
                : pageOcr.getRawText());
        }
    }

    private void postProcessLabels(AiLabels labels) {
        if (labels == null) {
            return;
        }
        OcrResult ocr = labels.getOcr();
        if (ocr == null) {
            return;
        }

        OcrPostProcessor.clean(ocr);

        // 材质优先级：文字明确说明的优先于视觉识别。
        // OCR/页面文字提取到材质描述且能解析出标签时，覆盖视觉直判结果；
        // 没有明确文字说明时才保留视觉识别结果
        if (StringUtils.hasText(ocr.getMaterialDescription())) {
            List<String> parsedMaterials = OcrPostProcessor.parseMaterials(ocr.getMaterialDescription());
            if (!parsedMaterials.isEmpty()) {
                labels.setMaterialTags(parsedMaterials);
            }
        }

        // 规范化尺寸：取解析结果的第一组有效尺寸写回 ocr.dimensions
        if (StringUtils.hasText(ocr.getDimensionText())) {
            List<Dimensions> parsed = OcrPostProcessor.parseDimensions(ocr.getDimensionText());
            if (!parsed.isEmpty()) {
                ocr.setDimensions(parsed.get(0));
            }
        }
    }

    private ImageEmbedding embedImageSafely(String rspuId, byte[] imageBytes) {
        try {
            return embeddingService.embedImageWithHash(new ByteArrayInputStream(imageBytes));
        } catch (Exception e) {
            log.error("生成图片 embedding 失败，rspuId={}", rspuId, e);
            return null;
        }
    }

    private void safeUpdateTaskStatus(String taskId, String status, int progress, String resultData, String errorMessage) {
        try {
            updateTaskStatus(taskId, status, progress, resultData, errorMessage);
        } catch (Exception ex) {
            log.error("更新任务状态异常，taskId={}", taskId, ex);
        }
    }

    private void updateTaskStatus(String taskId, String status, int progress, String resultData, String errorMessage) {
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("任务不存在，taskId={}", taskId);
            return;
        }
        // 终态保护：已进入终态的任务不允许被非终态（如迟到的进度更新）覆盖，防止状态回退
        if (isTerminalStatus(task.getStatus()) && !isTerminalStatus(status)) {
            log.warn("任务已处于终态 {}，忽略非终态更新 {}，taskId={}", task.getStatus(), status, taskId);
            return;
        }
        task.setStatus(status);
        task.setProgress(progress);
        task.setResultData(resultData);
        task.setErrorMessage(errorMessage);
        if (isTerminalStatus(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        asyncTaskMapper.updateById(task);
    }

    private boolean isTerminalStatus(String status) {
        return "done".equals(status) || "failed".equals(status) || "partial_success".equals(status);
    }

    /**
     * 同款检测：用本次 embedding 在向量存储召回，找到其他 RSPU 且相似度超阈值时，
     * 把当前产品标记为"存疑-疑似同款"（仅当仍为"待复核"，不覆盖人工/其他流程的复核结论）。
     *
     * @param rspuId    当前 RSPU ID
     * @param embedding 本次主图向量
     * @param operator  审计操作人（任务创建人）
     */
    private void flagDuplicateSuspect(String rspuId, float[] embedding, String operator) {
        List<VectorHit> hits = productVectorStore.search(embedding, 5, null, false);
        if (hits == null || hits.isEmpty()) {
            return;
        }
        for (VectorHit hit : hits) {
            String dupRspu = hit.rspuId();
            if (dupRspu == null || rspuId.equals(dupRspu)) {
                continue;
            }
            // cosine distance [0,2] 映射相似度 [0,1]；结果按距离升序，低于阈值即终止
            double similarity = Math.max(0.0, Math.min(1.0, 1.0 - hit.distance() / 2.0));
            if (similarity < duplicateSimilarThreshold) {
                return;
            }
            RspuMaster current = rspuMapper.selectById(rspuId);
            if (current == null || !"待复核".equals(current.getReviewStatus())) {
                return;
            }
            RspuMaster dup = rspuMapper.selectById(dupRspu.toString());
            String dupLabel = dup != null && StringUtils.hasText(dup.getRspuCode())
                ? dup.getRspuCode() : dupRspu.toString();
            String dupName = dup != null && StringUtils.hasText(dup.getProductName())
                ? "「" + dup.getProductName() + "」" : "";
            long percent = Math.round(similarity * 100);
            // 审计旧快照（P0-2）：与 AiRecognitionPersistenceService.markRspuAsDoubtful 的 logReview 口径对齐
            RspuMaster oldSnapshot = new RspuMaster();
            oldSnapshot.setRspuId(current.getRspuId());
            oldSnapshot.setReviewStatus(current.getReviewStatus());
            oldSnapshot.setReviewComment(current.getReviewComment());
            current.setReviewStatus("存疑");
            current.setReviewComment("疑似与 " + dupName + dupLabel + " 同款（向量相似度 "
                + percent + "%），请确认是否重复录入");
            current.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(current);
            auditLogService.logReview("rspu_master", rspuId, oldSnapshot, current, operator);
            log.info("疑似同款标记：rspuId={}，命中 {}，相似度 {}%", rspuId, dupLabel, percent);
            return;
        }
    }

    /**
     * 向量写入 pgvector（幂等，冲突覆盖）。写入时由存储层核验图片内容版本，
     * 编码期间图片被更新/删除则丢弃本次向量，由重建任务重新编码。
     *
     * @param imageId         图片 ID
     * @param currentRevision 编码前读取到的图片内容版本
     * @param embed           embedding 结果（向量 + 输入哈希）
     * @return 成功返回 null；失败返回降级文案（区分存储故障与图片已更新）
     */
    private String persistVector(String imageId, long currentRevision, ImageEmbedding embed) {
        try {
            productVectorStore.upsert(imageId, currentRevision, embed.inputHash(), embed.vector());
            log.info("向量已写入向量存储，imageId={}", imageId);
            return null;
        } catch (VectorStaleImageException e) {
            log.warn("图片内容已更新，向量已丢弃，将由重建任务重新编码，imageId={}", imageId, e);
            return "AI 识别完成，但图片内容已更新，向量已丢弃，将由重建任务重新编码";
        } catch (Exception e) {
            log.error("写入向量存储失败，imageId={}", imageId, e);
            return "AI 识别完成，但向量写入向量存储失败，以图搜图功能可能不可用";
        }
    }

    /**
     * 异步处理向量重建任务：重编码图片向量并写入 pgvector。
     *
     * @param taskId 任务 ID
     */
    @Async("taskExecutor")
    public void processVectorRebuild(String taskId) {
        log.info("开始异步处理向量重建任务，taskId={}", taskId);
        vectorRebuildService.executeRebuildTask(taskId);
    }
}
