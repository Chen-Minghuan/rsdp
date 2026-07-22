package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.StyleMatchResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.service.chroma.ChromaDbClient;
import com.rsdp.service.chroma.ChromaMetadataBuilder;
import com.rsdp.service.storage.StorageService;
import com.rsdp.util.OcrPostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
    private final ChromaDbClient chromaDbClient;
    private final StorageService storageService;
    private final AiRecognitionPersistenceService persistenceService;
    private final StyleMatchingService styleMatchingService;
    private final RspuVariantService rspuVariantService;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.ai.model}")
    private String aiModel;

    @Value("${rsdp.ai.max-image-size:20971520}")
    private long maxImageSize = 20 * 1024 * 1024;

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
                persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, msg);
                safeUpdateTaskStatus(taskId, "failed", 100, null, msg);
                return;
            }
        } catch (Exception e) {
            log.error("读取图片失败，taskId={}", taskId, e);
            persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
            return;
        }

        AiLabels labels;
        try (InputStream imageStream = new ByteArrayInputStream(imageBytes)) {
            long aiStart = System.currentTimeMillis();
            labels = visionService.recognizeImage(imageStream, categoryCode);
            processingTime = (int) (System.currentTimeMillis() - aiStart);

            // AI 标签后处理：清洗 OCR 字段，规范化尺寸，OCR 材质兜底
            postProcessLabels(labels);

            // 风格数据库校验：基于 style_matching_formula 计算风格匹配得分
            StyleMatchResult styleMatch = styleMatchingService.match(labels, rspuId);
            if (styleMatch != null) {
                labels.setConfidence(styleMatch.getConfidence());
                log.info("风格匹配评分完成，rspuId={}，style={}，score={}，confidence={}",
                    rspuId, styleMatch.getStyleCode(), styleMatch.getOverallScore(), styleMatch.getConfidence());
            }

            updateTaskStatus(taskId, "processing", 60, null, null);

            float[] embedding = embedImageSafely(rspuId, imageBytes);

            String productName = persistenceService.saveSuccess(taskId, rspuId, imageId, recognitionId, modelName,
                labels, processingTime, embedding);

            // AI 识别成功后，若该 RSPU 尚无变体，自动创建默认变体，便于后续批量绑定工厂报价
            rspuVariantService.initializeDefaultVariant(rspuId, labels);

            boolean vectorPersisted = false;
            String vectorError = null;
            if (embedding != null) {
                vectorPersisted = persistVector(imageId, rspuId, embedding, imageBytes.length);
                if (!vectorPersisted) {
                    vectorError = "AI 识别完成，但向量写入 ChromaDB 失败，以图搜图功能可能不可用";
                }
            } else {
                vectorError = "AI 识别完成，但生成图片向量失败，以图搜图功能可能不可用";
            }

            String finalStatus = vectorPersisted ? "done" : "partial_success";
            // resultData 供录入中心展示：AI 原始标签 + 最终生效的产品名称（OCR 品名或品类回退名）
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.valueToTree(labels);
            resultNode.put("productName", productName);
            updateTaskStatus(taskId, finalStatus, 100, objectMapper.writeValueAsString(resultNode), vectorError);
            log.info("产品录入异步任务完成，taskId={}，status={}", taskId, finalStatus);
        } catch (Exception e) {
            log.error("AI 识别失败，taskId={}", taskId, e);
            persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
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

        // 如果视觉识别没有返回材质标签，尝试用 OCR 材质描述兜底
        if ((labels.getMaterialTags() == null || labels.getMaterialTags().isEmpty())
            && StringUtils.hasText(ocr.getMaterialDescription())) {
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

    private float[] embedImageSafely(String rspuId, byte[] imageBytes) {
        try {
            return embeddingService.embedImage(new ByteArrayInputStream(imageBytes));
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

    private boolean persistVector(String imageId, String rspuId, float[] embedding, int imageSize) {
        try {
            RspuMaster rspu = persistenceService.getRspu(rspuId);
            if (rspu == null) {
                log.warn("写入向量时 RSPU 不存在，rspuId={}", rspuId);
                return false;
            }

            Map<String, Object> metadata = ChromaMetadataBuilder.buildProductMetadata(rspu, imageSize);

            chromaDbClient.upsert(
                List.of(imageId),
                List.of(embedding),
                List.of(metadata),
                null
            );
            log.info("向量已写入 ChromaDB，imageId={}", imageId);
            return true;
        } catch (Exception e) {
            log.error("写入 ChromaDB 失败，imageId={}", imageId, e);
            return false;
        }
    }
}
