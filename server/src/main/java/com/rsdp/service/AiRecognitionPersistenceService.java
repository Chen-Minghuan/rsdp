package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 识别结果持久化服务。
 *
 * <p>将 AI 识别成功/失败后的数据库写入操作封装为独立短事务，
 * 避免与外部 HTTP 调用（AI / Embedding）共享长事务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiRecognitionPersistenceService {

    private final RspuMapper rspuMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final AuditLogService auditLogService;
    private final DictResolverService dictResolverService;
    private final ObjectMapper objectMapper;

    /**
     * 查询 RSPU 主表记录（事务外读，仅用于向量 metadata 组装）。
     *
     * @param rspuId RSPU ID
     * @return RSPU 记录，不存在时返回 null
     */
    public RspuMaster getRspu(String rspuId) {
        return rspuMapper.selectById(rspuId);
    }

    /**
     * 在独立事务中保存 AI 识别成功结果。
     *
     * @param taskId         任务 ID
     * @param rspuId         RSPU ID
     * @param imageId        图片 ID
     * @param recognitionId  识别记录 ID
     * @param modelName      模型名称
     * @param labels         AI 识别标签
     * @param processingTime 处理耗时（毫秒）
     * @param embedding      图片 embedding（可为空）
     */
    @Transactional
    public void saveSuccess(String taskId, String rspuId, String imageId,
                            String recognitionId, String modelName,
                            AiLabels labels, int processingTime, float[] embedding) {
        String styleCode = dictResolverService.resolveCodeByName("style", labels.getStyle());
        List<String> sceneCodes = dictResolverService.resolveCodesByNames("scene", labels.getSceneTags());
        List<String> materialCodes = dictResolverService.resolveCodesByNames("material", labels.getMaterialTags());

        updateRspu(rspuId, labels, styleCode, materialCodes, sceneCodes, embedding, modelName);
        refreshStyleAssociations(rspuId, styleCode);
        refreshSceneAssociations(rspuId, sceneCodes);
        markImageProcessed(imageId);
        insertRecognitionRecord(taskId, rspuId, imageId, recognitionId, modelName, labels, processingTime, "success", null);
    }

    /**
     * 在独立事务中保存 AI 识别失败结果，并将 RSPU 标记为存疑。
     *
     * @param taskId        任务 ID
     * @param rspuId        RSPU ID
     * @param imageId       图片 ID
     * @param recognitionId 识别记录 ID
     * @param modelName     模型名称
     * @param errorMessage  错误信息
     */
    @Transactional
    public void saveFailure(String taskId, String rspuId, String imageId,
                            String recognitionId, String modelName, String errorMessage) {
        insertRecognitionRecord(taskId, rspuId, imageId, recognitionId, modelName, null, 0, "failed", errorMessage);
        markRspuAsDoubtful(rspuId, modelName);
    }

    private void updateRspu(String rspuId, AiLabels labels, String styleCode,
                            List<String> materialCodes, List<String> sceneCodes,
                            float[] embedding, String modelName) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            log.warn("保存识别结果时 RSPU 不存在，rspuId={}", rspuId);
            return;
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        rspu.setPositioningLabel(styleCode != null ? styleCode : labels.getStyle());
        rspu.setSixDimTags(toJson(labels.getSixDimTags()));
        rspu.setColorPrimaryName(labels.getColorPrimaryName());
        rspu.setColorPrimaryHsv(toJson(labels.getColorPrimaryHsv()));
        rspu.setMaterialTags(toJson(materialCodes));
        rspu.setSceneTags(toJson(sceneCodes));
        if (embedding != null) {
            rspu.setStyleVector(toJson(embedding));
        }
        rspu.setAestheticsConfidence(labels.getConfidence());
        rspu.setSourceAgentVersion(modelName);
        rspu.setStatus("active");
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
    }

    private void markRspuAsDoubtful(String rspuId, String modelName) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            log.warn("标记 RSPU 存疑时记录不存在，rspuId={}", rspuId);
            return;
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        rspu.setStatus("active");
        rspu.setReviewStatus("存疑");
        rspu.setSourceAgentVersion(modelName);
        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logReview("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
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

    private void markImageProcessed(String imageId) {
        ImageAssets imageAsset = imageAssetsMapper.selectById(imageId);
        if (imageAsset != null) {
            imageAsset.setAiProcessed(true);
            imageAssetsMapper.updateById(imageAsset);
        }
    }

    private void insertRecognitionRecord(String taskId, String rspuId, String imageId,
                                         String recognitionId, String modelName,
                                         AiLabels labels, int processingTime,
                                         String status, String errorMessage) {
        AiRecognition rec = new AiRecognition();
        rec.setRecognitionId(recognitionId);
        rec.setImageId(imageId);
        rec.setRspuId(rspuId);
        rec.setTaskId(taskId);
        rec.setModelName(modelName);
        rec.setRecognitionType("label");
        rec.setEndpoint("/chat/completions");
        rec.setStatus(status);
        rec.setProcessingTimeMs(processingTime);
        rec.setCreatedAt(LocalDateTime.now());

        if (labels != null) {
            rec.setOutputData(toJson(labels));
            rec.setParsedStyle(labels.getStyle());
            rec.setParsedSixDim(toJson(labels.getSixDimTags()));
            rec.setParsedColorHsv(toJson(labels.getColorPrimaryHsv()));
            rec.setParsedSceneTags(toJson(labels.getSceneTags()));
            rec.setConfidence(labels.getConfidence());
        }

        if (errorMessage != null) {
            rec.setErrorMessage(errorMessage);
        }

        aiRecognitionMapper.insert(rec);
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
        copy.setReviewComment(source.getReviewComment());
        copy.setAestheticsConfidence(source.getAestheticsConfidence());
        copy.setProductLevel(source.getProductLevel());
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
