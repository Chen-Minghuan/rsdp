package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.OcrResult;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.service.chroma.ChromaDbClient;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final VisionService visionService;
    private final EmbeddingService embeddingService;
    private final ChromaDbClient chromaDbClient;
    private final StorageService storageService;
    private final AiRecognitionPersistenceService persistenceService;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.ai.model}")
    private String aiModel;

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
        updateTaskStatus(taskId, "processing", 10, null, null);

        String recognitionId = "REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String modelName = aiModel;
        int processingTime = 0;

        byte[] imageBytes;
        try (InputStream imageStream = storageService.get(objectKey)) {
            imageBytes = imageStream.readAllBytes();
        } catch (Exception e) {
            log.error("读取图片失败，taskId={}", taskId, e);
            persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
            return;
        }

        AiLabels labels;
        try (InputStream imageStream = new ByteArrayInputStream(imageBytes)) {
            long aiStart = System.currentTimeMillis();
            labels = visionService.recognizeImage(imageStream);
            processingTime = (int) (System.currentTimeMillis() - aiStart);

            // OCR 结果后处理：清洗型号/材质，规范化尺寸与材质标签
            postProcessOcr(labels);

            updateTaskStatus(taskId, "processing", 60, null, null);

            float[] embedding = embedImageSafely(rspuId, imageBytes);

            persistenceService.saveSuccess(taskId, rspuId, imageId, recognitionId, modelName,
                labels, processingTime, embedding);

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
            updateTaskStatus(taskId, finalStatus, 100, objectMapper.writeValueAsString(labels), vectorError);
            log.info("产品录入异步任务完成，taskId={}，status={}", taskId, finalStatus);
        } catch (Exception e) {
            log.error("AI 识别失败，taskId={}", taskId, e);
            persistenceService.saveFailure(taskId, rspuId, imageId, recognitionId, modelName, e.getMessage());
            safeUpdateTaskStatus(taskId, "failed", 100, null, e.getMessage());
        }
    }

    private void postProcessOcr(AiLabels labels) {
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
            List<String> parsedMaterials = OcrPostProcessor.parseMaterials(
                ocr.getMaterialDescription(), labels.getMaterialTags());
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

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("rspu_id", rspuId);
            metadata.put("category_code", rspu.getCategoryCode());
            metadata.put("positioning_label", rspu.getPositioningLabel());
            metadata.put("color_primary_name", rspu.getColorPrimaryName());
            metadata.put("material_tags", rspu.getMaterialTags());
            metadata.put("scene_tags", rspu.getSceneTags());
            metadata.put("status", rspu.getStatus());
            metadata.put("image_size", imageSize);

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
