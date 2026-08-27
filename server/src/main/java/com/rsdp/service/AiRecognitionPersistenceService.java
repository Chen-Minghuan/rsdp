package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.util.SizeSpecParser;
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
    private final RspuVariantMapper rspuVariantMapper;
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
     * @return 最终生效的产品名称（OCR 品名或品类回退名；无则 null）
     */
    @Transactional
    public String saveSuccess(String taskId, String rspuId, String imageId,
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

        String productName = updateRspu(rspuId, labels, styleCode, materialCodes, fabricCodes, sceneCodes, embedding, modelName);
        refreshStyleAssociations(rspuId, styleCode, secondaryStyleCodes);
        refreshSceneAssociations(rspuId, sceneCodes);
        markImageProcessed(imageId);
        insertRecognitionRecord(taskId, rspuId, imageId, recognitionId, modelName, labels, processingTime, "success", null);
        return productName;
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

    private String updateRspu(String rspuId, AiLabels labels, String styleCode,
                            List<String> materialCodes, List<String> fabricCodes, List<String> sceneCodes,
                            float[] embedding, String modelName) {
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null) {
            log.warn("保存识别结果时 RSPU 不存在，rspuId={}", rspuId);
            return null;
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
        // 产品名称：优先 AI OCR 提取；图上无文字时回退品类名（如「座椅」）；人工/Excel 已填不覆盖
        if (!StringUtils.hasText(rspu.getProductName())) {
            String ocrName = labels.getOcr() != null ? labels.getOcr().getProductName() : null;
            if (StringUtils.hasText(ocrName) && !isUnidentifiedName(ocrName)) {
                rspu.setProductName(ocrName);
            } else {
                String categoryName = dictResolverService.resolveNameByCode("category", rspu.getCategoryCode());
                // resolveNameByCode 找不到时返回原码，原码不作为名称使用
                if (StringUtils.hasText(categoryName) && !categoryName.equals(rspu.getCategoryCode())) {
                    rspu.setProductName(categoryName);
                }
            }
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
        return rspu.getProductName();
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

    /**
     * AI 识别补全风格后补发 RSPU 业务编码（rspu_code 为空时）。
     *
     * <p>品类用 rspu.category_code，风格用新补的风格码（无则用现有定位标签），
     * 尺寸码用 {@link RspuCodeService#inferSizeCode} 的 AI 尺寸推断 + 字典感知降级能力；
     * 推断不出尺寸/风格按既有「存疑」语义留空不阻断。assignCode 本身幂等
     * （已有 code 直接返回），发号失败（含非业务异常）捕获降级，不影响识别主流程。</p>
     */
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
            // OCR 无尺寸时按变体尺寸回退（dimensions JSON / sizeText 经 SizeSpecParser 解析）
            inferredSizeCode = inferSizeCodeFromVariants(rspu.getRspuId());
        }
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
            String code = rspuCodeService.assignCode(rspu.getRspuId(), categoryCode,
                effectiveStyleCode, inferredSizeCode);
            // assignCode 内部已落库；同步到当前实体，避免后续 updateById 用旧快照覆盖
            rspu.setRspuCode(code);
            log.info("AI 识别补全风格后补发 RSPU 业务编码成功，rspuId={}，rspuCode={}", rspu.getRspuId(), code);
        } catch (BusinessException e) {
            log.warn("AI 识别后生成 RSPU 业务编码失败，rspuId={}，原因={}", rspu.getRspuId(), e.getMessage());
            rspu.setReviewStatus("存疑");
            rspu.setReviewComment("生成业务编码失败: " + e.getMessage());
        } catch (Exception e) {
            // 非业务异常（基础设施故障等）同样降级：标记存疑但不中断识别结果落库
            log.warn("AI 识别后补发 RSPU 业务编码异常，rspuId={}", rspu.getRspuId(), e);
            rspu.setReviewStatus("存疑");
            rspu.setReviewComment("生成业务编码失败: " + e.getMessage());
        }
    }

    /**
     * 从变体尺寸回退推断尺寸码：取该 RSPU 全部未删除变体的 dimensions JSON
     * （{"w","d","h","unit"}）与 sizeText（经 SizeSpecParser 解析）中的最大边毫米数，
     * 再走 {@link RspuCodeService#inferSizeCodeFromMm} 阈值推断。无任何可用尺寸返回 null。
     *
     * @param rspuId RSPU ID
     * @return 尺寸码或 null
     */
    private String inferSizeCodeFromVariants(String rspuId) {
        List<RspuVariant> variants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", rspuId));
        long maxMm = 0;
        for (RspuVariant variant : variants) {
            maxMm = Math.max(maxMm, maxVariantDimensionMm(variant));
        }
        return maxMm > 0 ? rspuCodeService.inferSizeCodeFromMm(maxMm) : null;
    }

    /**
     * 单个变体的最大边毫米数（dimensions JSON 优先，sizeText 解析兜底；解析失败按 0）。
     */
    private long maxVariantDimensionMm(RspuVariant variant) {
        long max = maxFromDimensionsJson(variant.getDimensions());
        if (max <= 0 && StringUtils.hasText(variant.getSizeText())) {
            for (SizeSpecParser.SizeSpec spec : SizeSpecParser.parse(variant.getSizeText(), null)) {
                Dimensions dims = spec.dimensions();
                if (dims != null) {
                    max = Math.max(max, maxOf(dims));
                }
            }
        }
        return max;
    }

    private long maxFromDimensionsJson(String dimensionsJson) {
        if (!StringUtils.hasText(dimensionsJson)) {
            return 0;
        }
        try {
            Dimensions dims = objectMapper.readValue(dimensionsJson, Dimensions.class);
            return maxOf(dims);
        } catch (Exception e) {
            log.debug("变体 dimensions JSON 解析失败，按无尺寸处理: {}", dimensionsJson);
            return 0;
        }
    }

    /**
     * 尺寸对象的最大边（统一换算毫米）。
     */
    private long maxOf(Dimensions dims) {
        long max = Math.max(dims.getW() != null ? dims.getW() : 0,
            Math.max(dims.getD() != null ? dims.getD() : 0, dims.getH() != null ? dims.getH() : 0));
        String unit = dims.getUnit() != null ? dims.getUnit().trim().toLowerCase() : "mm";
        double factor = switch (unit) {
            case "cm" -> 10.0;
            case "m" -> 1000.0;
            case "inch" -> 25.4;
            default -> 1.0;
        };
        return Math.round(max * factor);
    }

    /**
     * 定位标签是否为空缺（null/空串/「待识别」占位），空缺时允许 AI 填充。
     */
    private boolean isBlankOrUnidentified(String value) {
        return !StringUtils.hasText(value) || "待识别".equals(value.trim());
    }

    /**
     * AI 给出的"无名称"占位值（未知/待识别/unknown）不作为产品名称使用——
     * 图上无文字时模型会返回这类占位，落入名称字段会覆盖掉后续回退品类名的机会。
     */
    private boolean isUnidentifiedName(String value) {
        String v = value.trim();
        return "未知".equals(v) || "待识别".equals(v) || "unknown".equalsIgnoreCase(v);
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
