package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.service.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务处理器，负责在后台执行 AI 识别等耗时操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskProcessor {

    private final RspuMapper rspuMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final VisionService visionService;
    private final StorageService storageService;
    private final AuditLogService auditLogService;
    private final DictResolverService dictResolverService;
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
    @Async
    @Transactional
    public void processProductEntry(String taskId, String rspuId, String imageId, String objectKey) {
        log.info("开始异步处理产品录入任务，taskId={}", taskId);
        updateTaskStatus(taskId, "processing", 10, null, null);

        String recognitionId = "REC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String modelName = aiModel;
        String status = "success";
        String errorMessage = null;
        AiLabels labels = null;
        int processingTime = 0;

        try (InputStream imageStream = storageService.get(objectKey)) {
            long aiStart = System.currentTimeMillis();
            labels = visionService.recognizeImage(imageStream);
            processingTime = (int) (System.currentTimeMillis() - aiStart);

            updateTaskStatus(taskId, "processing", 60, null, null);
            persistSuccessResult(taskId, rspuId, imageId, recognitionId, modelName, labels, processingTime);
            updateTaskStatus(taskId, "done", 100, objectMapper.writeValueAsString(labels), null);

            log.info("产品录入异步任务完成，taskId={}", taskId);
        } catch (Exception e) {
            log.error("AI 识别失败，taskId={}", taskId, e);
            status = "failed";
            errorMessage = e.getMessage();

            persistFailureResult(taskId, rspuId, imageId, recognitionId, modelName, errorMessage);
            try {
                updateTaskStatus(taskId, "failed", 100, null, errorMessage);
            } catch (Exception ex) {
                log.error("更新任务失败状态异常，taskId={}", taskId, ex);
            }
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
        if ("done".equals(status) || "failed".equals(status)) {
            task.setCompletedAt(LocalDateTime.now());
        }
        asyncTaskMapper.updateById(task);
    }

    private void persistSuccessResult(String taskId, String rspuId, String imageId,
                                        String recognitionId, String modelName,
                                        AiLabels labels, int processingTime) {
        // 解析风格/场景/材质字典码
        String styleCode = dictResolverService.resolveCodeByName("style", labels.getStyle());
        List<String> sceneCodes = dictResolverService.resolveCodesByNames("scene", labels.getSceneTags());
        List<String> materialCodes = dictResolverService.resolveCodesByNames("material", labels.getMaterialTags());

        // 更新 RSPU
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu != null) {
            RspuMaster oldSnapshot = snapshot(rspu);
            // 优先保存风格码；无法解析时回退保存原始中文名
            rspu.setPositioningLabel(styleCode != null ? styleCode : labels.getStyle());
            rspu.setSixDimTags(toJson(labels.getSixDimTags()));
            rspu.setColorPrimaryName(labels.getColorPrimaryName());
            rspu.setColorPrimaryHsv(toJson(labels.getColorPrimaryHsv()));
            rspu.setMaterialTags(toJson(materialCodes));
            rspu.setSceneTags(toJson(labels.getSceneTags()));
            rspu.setAestheticsConfidence(labels.getConfidence());
            rspu.setSourceAgentVersion(modelName);
            rspu.setStatus("active");
            rspu.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(rspu);
            auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, "admin");

            // 刷新风格/场景关联表
            refreshStyleAssociations(rspuId, styleCode);
            refreshSceneAssociations(rspuId, sceneCodes);
        }

        // 更新图片为已识别
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset != null) {
            imageAsset.setAiProcessed(true);
            imageAssetsMapper.updateById(imageAsset);
        }

        // 写入 AI 识别记录
        AiRecognition rec = new AiRecognition();
        rec.setRecognitionId(recognitionId);
        rec.setImageId(imageId);
        rec.setRspuId(rspuId);
        rec.setTaskId(taskId);
        rec.setModelName(modelName);
        rec.setRecognitionType("label");
        rec.setEndpoint("/chat/completions");
        rec.setOutputData(toJson(labels));
        rec.setParsedStyle(labels.getStyle());
        rec.setParsedSixDim(toJson(labels.getSixDimTags()));
        rec.setParsedColorHsv(toJson(labels.getColorPrimaryHsv()));
        rec.setParsedSceneTags(toJson(labels.getSceneTags()));
        rec.setConfidence(labels.getConfidence());
        rec.setProcessingTimeMs(processingTime);
        rec.setStatus("success");
        rec.setCreatedAt(LocalDateTime.now());
        aiRecognitionMapper.insert(rec);
    }

    private void refreshStyleAssociations(String rspuId, String styleCode) {
        rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        if (styleCode == null || styleCode.isBlank()) {
            return;
        }
        RspuStyle style = new RspuStyle();
        style.setRspuId(rspuId);
        style.setDictType("style");
        style.setStyleCode(styleCode);
        style.setIsPrimary(true);
        style.setCreatedAt(LocalDateTime.now());
        rspuStyleMapper.insert(style);
    }

    private void refreshSceneAssociations(String rspuId, List<String> sceneCodes) {
        rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        if (sceneCodes == null || sceneCodes.isEmpty()) {
            return;
        }
        for (String sceneCode : sceneCodes) {
            RspuScene scene = new RspuScene();
            scene.setRspuId(rspuId);
            scene.setDictType("scene");
            scene.setSceneCode(sceneCode);
            scene.setCreatedAt(LocalDateTime.now());
            rspuSceneMapper.insert(scene);
        }
    }

    private void persistFailureResult(String taskId, String rspuId, String imageId,
                                        String recognitionId, String modelName, String errorMessage) {
        // 记录 AI 识别失败
        AiRecognition rec = new AiRecognition();
        rec.setRecognitionId(recognitionId);
        rec.setImageId(imageId);
        rec.setRspuId(rspuId);
        rec.setTaskId(taskId);
        rec.setModelName(modelName);
        rec.setRecognitionType("label");
        rec.setEndpoint("/chat/completions");
        rec.setStatus("failed");
        rec.setErrorMessage(errorMessage);
        rec.setCreatedAt(LocalDateTime.now());
        aiRecognitionMapper.insert(rec);

        // RSPU 保持 active，但 review_status 为存疑
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu != null) {
            RspuMaster oldSnapshot = snapshot(rspu);
            rspu.setStatus("active");
            rspu.setReviewStatus("存疑");
            rspu.setUpdatedAt(LocalDateTime.now());
            rspuMapper.updateById(rspu);
            auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, "admin");
        }
    }

    private RspuMaster snapshot(RspuMaster source) {
        RspuMaster copy = new RspuMaster();
        copy.setRspuId(source.getRspuId());
        copy.setCategoryCode(source.getCategoryCode());
        copy.setCategoryPath(source.getCategoryPath());
        copy.setPositioningLabel(source.getPositioningLabel());
        copy.setColorPrimaryName(source.getColorPrimaryName());
        copy.setColorPrimaryHsv(source.getColorPrimaryHsv());
        copy.setMaterialTags(source.getMaterialTags());
        copy.setSceneTags(source.getSceneTags());
        copy.setSixDimTags(source.getSixDimTags());
        copy.setStatus(source.getStatus());
        copy.setReviewStatus(source.getReviewStatus());
        copy.setAestheticsConfidence(source.getAestheticsConfidence());
        copy.setSourceAgentVersion(source.getSourceAgentVersion());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JSON 序列化失败", e);
            return "{}";
        }
    }
}
