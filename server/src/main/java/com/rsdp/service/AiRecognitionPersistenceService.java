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
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final RspuCodeService rspuCodeService;
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
        List<String> secondaryStyleCodes = dictResolverService.resolveCodesByNames("style", labels.getSecondaryStyles());
        List<String> sceneCodes = dictResolverService.resolveCodesByNames("scene", labels.getSceneTags());
        // E 维（表面材质）与材质标签同源：E 值并入材质解析候选，统一走 material 字典归一
        List<String> materialCandidates = new java.util.ArrayList<>(
            labels.getMaterialTags() != null ? labels.getMaterialTags() : List.of());
        String dimE = labels.getSixDimTags() != null ? labels.getSixDimTags().get("E") : null;
        if (StringUtils.hasText(dimE)) {
            materialCandidates.add(dimE.trim());
        }
        List<String> materialCodes = dictResolverService.resolveCodesByNames("material", materialCandidates);
        List<String> fabricCodes = dictResolverService.resolveCodesByNames("fabric", labels.getFabricTags());

        updateRspu(rspuId, labels, styleCode, materialCodes, fabricCodes, sceneCodes, embedding, modelName);
        refreshStyleAssociations(rspuId, styleCode, secondaryStyleCodes);
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
                            List<String> materialCodes, List<String> fabricCodes, List<String> sceneCodes,
                            float[] embedding, String modelName) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            log.warn("保存识别结果时 RSPU 不存在，rspuId={}", rspuId);
            return;
        }

        RspuMaster oldSnapshot = snapshot(rspu);
        // 人工/Excel 已明确提供的字段不被 AI 覆盖，AI 只补空缺
        // （来源判断 = 字段是否为空；Excel 导入与人工录入提供过的字段必然非空）
        if (isBlankOrUnidentified(rspu.getPositioningLabel())) {
            rspu.setPositioningLabel(styleCode != null ? styleCode : labels.getStyle());
        }
        if (isEmptyJson(rspu.getSixDimTags(), "{}")) {
            rspu.setSixDimTags(toJson(normalizeSixDimTags(labels.getSixDimTags(), rspu.getCategoryCode(), materialCodes)));
        }
        if (!StringUtils.hasText(rspu.getColorPrimaryName())) {
            rspu.setColorPrimaryName(labels.getColorPrimaryName());
        }
        // 商品名称：取 OCR 识别结果，同样只补空缺
        if (!StringUtils.hasText(rspu.getProductName()) && labels.getOcr() != null
                && StringUtils.hasText(labels.getOcr().getProductName())) {
            rspu.setProductName(labels.getOcr().getProductName());
        }
        if (isEmptyJson(rspu.getColorPrimaryHsv(), "[]")) {
            rspu.setColorPrimaryHsv(toJson(labels.getColorPrimaryHsv()));
        }
        if (isEmptyJson(rspu.getMaterialTags(), "[]")) {
            rspu.setMaterialTags(toJson(materialCodes));
        }
        // 面料标签：与材质同模式，AI 只补空缺。
        // 面料由同一次 AI 调用综合图片文字与视觉输出（模型看图同时读字），
        // 无需像材质那样再做 OCR 文字覆盖
        if (isEmptyJson(rspu.getFabricTags(), "[]")) {
            rspu.setFabricTags(toJson(fabricCodes));
        }
        if (isEmptyJson(rspu.getSceneTags(), "[]")) {
            rspu.setSceneTags(toJson(sceneCodes));
        }
        // 向量与置信度是 AI 识别产物（无人工来源），始终更新
        if (embedding != null) {
            rspu.setStyleVector(toJson(embedding));
        }
        rspu.setAestheticsConfidence(labels.getConfidence());
        rspu.setSourceAgentVersion(modelName);
        rspu.setStatus("active");

        // AI 识别后尝试生成 RSPU 业务编码；无法推断尺寸或风格时标记为存疑
        assignRspuCodeIfPossible(rspu, labels, styleCode);

        rspu.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(rspu);
        auditLogService.logUpdate("rspu_master", rspuId, oldSnapshot, rspu, SecurityOperatorContext.currentUsername());
    }

    /**
     * 六维标签归一（P1 枚举化）：AI 输出的枚举中文名/别名替换为带品类前缀的 dict_code
     * （如 SF-宽厚扶手），筛选/精确匹配用码、展示用名；未命中保留原文并记日志
     * （供字典运营补充枚举/别名）。
     *
     * <p>E 维度（表面材质）与材质标签同源（P3-⑤）：E 值已并入 material 字典解析候选，
     * 此处 E 直接取第一个归一材质码的中文名（与 materialTags 展示同源，避免两处不一致）；
     * 材质未归一时保留 AI 输出的 E 原文。</p>
     *
     * @param sixDimTags    AI 输出的六维标签（原始 map 不被修改，识别记录留档用原文）
     * @param categoryCode  RSPU 品类码
     * @param materialCodes 归一后的材质字典码列表（含 E 值并入的候选）
     * @return 归一后的六维标签 map
     */
    private java.util.Map<String, String> normalizeSixDimTags(java.util.Map<String, String> sixDimTags, String categoryCode,
                                                              List<String> materialCodes) {
        if (sixDimTags == null || sixDimTags.isEmpty()) {
            return sixDimTags;
        }
        java.util.Map<String, String> normalized = new java.util.LinkedHashMap<>(sixDimTags);
        sixDimTags.forEach((dim, value) -> {
            if ("E".equalsIgnoreCase(dim) || value == null || value.isBlank()) {
                return;
            }
            String code = dictResolverService.resolveSixDimCode(dim, categoryCode, value);
            if (code != null) {
                normalized.put(dim, code);
            } else {
                log.info("六维标签未命中字典枚举，保留原文: dim={}, category={}, value={}", dim, categoryCode, value);
            }
        });
        if (materialCodes != null && !materialCodes.isEmpty()) {
            String materialName = dictResolverService.resolveNameByCode("material", materialCodes.get(0));
            if (StringUtils.hasText(materialName)) {
                normalized.put("E", materialName);
            }
        }
        return normalized;
    }

    private void assignRspuCodeIfPossible(RspuMaster rspu, AiLabels labels, String styleCode) {
        if (StringUtils.hasText(rspu.getRspuCode())) {
            return;
        }
        String categoryCode = rspu.getCategoryCode();
        if (!StringUtils.hasText(categoryCode)) {
            return;
        }
        String inferredSizeCode = rspuCodeService.inferSizeCode(labels);
        if (!StringUtils.hasText(inferredSizeCode)) {
            rspu.setReviewStatus("存疑");
            rspu.setReviewComment("无法推断尺寸码，需补充尺寸后生成业务编码");
            return;
        }
        String effectiveStyleCode = StringUtils.hasText(styleCode) ? styleCode : rspu.getPositioningLabel();
        if (!StringUtils.hasText(effectiveStyleCode) || "待识别".equals(effectiveStyleCode)) {
            rspu.setReviewStatus("存疑");
            rspu.setReviewComment("无法确定风格码，需补充风格后生成业务编码");
            return;
        }
        try {
            String code = rspuCodeService.generateNextCode(categoryCode, effectiveStyleCode, inferredSizeCode);
            rspu.setRspuCode(code);
        } catch (BusinessException e) {
            log.warn("AI 识别后生成 RSPU 业务编码失败，rspuId={}，原因={}", rspu.getRspuId(), e.getMessage());
            rspu.setReviewStatus("存疑");
            rspu.setReviewComment("生成业务编码失败: " + e.getMessage());
        }
    }

    /**
     * 定位标签是否为空缺（null/空串/「待识别」占位），空缺时允许 AI 填充。
     */
    private boolean isBlankOrUnidentified(String value) {
        return !StringUtils.hasText(value) || "待识别".equals(value.trim());
    }

    /**
     * JSON 字段是否为空缺（null/空串/空数组/空对象），空缺时允许 AI 填充。
     */
    private boolean isEmptyJson(String value, String emptyForm) {
        return !StringUtils.hasText(value) || emptyForm.equals(value.trim());
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

    private void refreshStyleAssociations(String rspuId, String styleCode, List<String> secondaryStyleCodes) {
        // 人工/Excel 已明确提供风格关联时不覆盖，AI 只补空缺
        Long existing = rspuStyleMapper.selectCount(new QueryWrapper<RspuStyle>().eq("rspu_id", rspuId));
        if (existing != null && existing > 0) {
            return;
        }
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
        // 备选风格（AI 识别输出，去重且不与主风格重复）
        if (secondaryStyleCodes == null) {
            return;
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        seen.add(styleCode);
        for (String secondaryCode : secondaryStyleCodes) {
            if (secondaryCode == null || secondaryCode.isBlank() || !seen.add(secondaryCode)) {
                continue;
            }
            RspuStyle secondary = new RspuStyle();
            secondary.setRspuId(rspuId);
            secondary.setDictType("style");
            secondary.setStyleCode(secondaryCode);
            secondary.setIsPrimary(false);
            secondary.setCreatedAt(LocalDateTime.now());
            rspuStyleMapper.insert(secondary);
        }
    }

    private void refreshSceneAssociations(String rspuId, List<String> sceneCodes) {
        // 人工/Excel 已明确提供场景关联时不覆盖，AI 只补空缺
        Long existing = rspuSceneMapper.selectCount(new QueryWrapper<RspuScene>().eq("rspu_id", rspuId));
        if (existing != null && existing > 0) {
            return;
        }
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
        copy.setFabricTags(source.getFabricTags());
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
