package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuMergeRequest;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuDuplicateSuspect;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuScene;
import com.rsdp.entity.RspuStyle;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.RspuDuplicateSuspectMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuMergeMapper;
import com.rsdp.mapper.RspuRelationMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.RspuStyleMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 同款产品合并服务（决策点②共享主档模型：平台归一去重的执行器）。
 *
 * <p>把重复副本 RSPU 合并到目标 RSPU：字段仅补空缺（可指定取副本值）、风格/场景并集、
 * 变体按 (尺寸,颜色,材质) key 自动映射或改挂、RSKU 迁移（冲突必须人工裁决）、图片改挂
 * （imageId 主键不变，pgvector 向量无需重建）、全部引用表改指、双投影重算，
 * 最后副本走既有 deleteProduct 软删（回收站可见，删除审计与向量清理复用既有链路）。</p>
 *
 * <p>合并视为逻辑不可逆：迁移映射快照写入审计 detail 供人工补救参考，不提供一键撤销。
 * 仅平台员工可执行（共享主数据的结构性操作，不收口到工厂）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RspuMergeService {

    /** 允许参与归并的主档字段白名单（关联表/编码/状态类字段不在此列，走专门逻辑） */
    private static final Set<String> MERGEABLE_FIELDS = Set.of(
        "productName", "description", "retailPrice", "colorPrimaryName", "materialTags",
        "fabricTags", "sixDimTags", "referencePriceBand", "productLevel", "warrantyYears", "keySpecs");

    private final RspuMapper rspuMapper;
    private final RspuStyleMapper rspuStyleMapper;
    private final RspuSceneMapper rspuSceneMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final ImageAssetsMapper imageAssetsMapper;
    private final RspuDuplicateSuspectMapper duplicateSuspectMapper;
    private final RspuMergeMapper rspuMergeMapper;
    private final RspuRelationMapper rspuRelationMapper;
    private final AuditLogService auditLogService;
    private final RspuPriceSummaryService rspuPriceSummaryService;
    private final RskuCodeService rskuCodeService;
    private final ProductQueryService productQueryService;
    private final ObjectMapper objectMapper;

    /**
     * 执行同款合并（单事务；任何一步失败整体回滚，不产生半合并状态）。
     *
     * @param request 合并请求（副本/目标 + 字段覆盖选择 + RSKU 冲突裁决）
     * @return 合并结果统计（迁移的变体/RSKU/图片数量、重发编码数）
     */
    @Transactional
    public Map<String, Object> merge(RspuMergeRequest request) {
        String sourceId = request.getSourceRspuId().trim();
        String targetId = request.getTargetRspuId().trim();
        String operator = SecurityOperatorContext.currentUsername();

        // ---- 1. 守卫 ----
        if (!SecurityOperatorContext.isPlatformStaff()) {
            throw new BusinessException("同款合并仅平台运营人员可执行");
        }
        if (sourceId.equals(targetId)) {
            throw new BusinessException("副本与目标不能是同一个产品");
        }
        RspuMaster source = rspuMapper.selectById(sourceId);
        if (source == null) {
            throw new ResourceNotFoundException("副本产品不存在: " + sourceId);
        }
        RspuMaster target = rspuMapper.selectById(targetId);
        if (target == null) {
            throw new ResourceNotFoundException("目标产品不存在: " + targetId);
        }
        if ("processing".equals(target.getStatus())) {
            throw new BusinessException("目标产品仍在识别中，请等待识别完成后再合并");
        }
        if ("processing".equals(source.getStatus())) {
            throw new BusinessException("副本产品仍在识别中，请等待识别完成后再合并");
        }
        List<String> takeSourceFields = request.getTakeSourceFields() == null
            ? List.of() : request.getTakeSourceFields();
        for (String field : takeSourceFields) {
            if (!MERGEABLE_FIELDS.contains(field)) {
                throw new BusinessException("字段不允许从副本覆盖: " + field);
            }
        }

        // ---- 2. 字段归并：目标已有值不动、仅补空缺；takeSourceFields 显式取副本值 ----
        RspuMaster targetOldSnapshot = snapshotMergeableFields(target);
        List<String> changedFields = applyFieldMerge(source, target, takeSourceFields);

        // ---- 3. 风格/场景并集重写（副本的关联随副本软删物理删除不可恢复，必须先并集） ----
        List<String> unionSceneCodes = mergeStyleAssociations(sourceId, target, operator);
        target.setSceneTags(toJsonQuietly(unionSceneCodes));
        target.setUpdatedAt(LocalDateTime.now());
        rspuMapper.updateById(target);
        if (!changedFields.isEmpty()) {
            auditLogService.logUpdate("rspu_master", targetId, targetOldSnapshot,
                snapshotMergeableFields(target), operator);
        }

        // ---- 4. 变体映射：uk_variant_attrs 同 key → 映射到目标变体；否则改挂到目标 ----
        Map<String, String> variantIdMap = new HashMap<>();
        int movedVariantCount = migrateVariants(sourceId, targetId, variantIdMap);

        // ---- 5. RSKU 迁移（先全量校验冲突裁决齐不齐，再执行；冲突未裁决整体拒绝） ----
        Map<String, Object> rskuStats = migrateRskus(sourceId, targetId, variantIdMap,
            request.getRskuConflictResolutions(), operator);

        // ---- 6. rsku_code 重发（编码含源 rspu_code 段，迁移行已置空，统一补发） ----
        int reissuedCodes = rskuCodeService.backfillCodesByRspu(targetId);

        // ---- 7. 图片改挂（主键 imageId 不变 → pgvector 向量行无需动，检索经 JOIN 自动跟随） ----
        int movedImageCount = migrateImages(sourceId, targetId, variantIdMap);

        // ---- 8. 引用表改指（先改指再删副本，deleteProduct 级联才不会误伤已迁数据） ----
        repointReferences(sourceId, targetId);

        // ---- 9. 双投影重算（源副本上的 RSKU 已迁走/软删，目标新增迁移 RSKU） ----
        rspuPriceSummaryService.recalculate(sourceId);
        rspuPriceSummaryService.recalculate(targetId);

        // ---- 10. 疑似同款配对闭环：本配对 merged，副本其余 pending 配对一并 dismissed ----
        closeSuspectPairs(sourceId, targetId, operator);

        // ---- 11. 副本软删（复用既有 deleteProduct：级联清理残留变体/关系 + 审计 + 向量事件） ----
        productQueryService.deleteProduct(sourceId);

        auditLogService.logUpdate("rspu_master", targetId, null, Map.of(
            "action", "merge",
            "sourceRspuId", sourceId,
            "sourceRspuCode", source.getRspuCode() == null ? "" : source.getRspuCode(),
            "changedFields", changedFields,
            "rsku", rskuStats,
            "movedVariants", movedVariantCount,
            "movedImages", movedImageCount,
            "reissuedRskuCodes", reissuedCodes), operator);
        log.info("同款合并完成：{} → {}，迁移 RSKU {} 条、变体 {} 条、图片 {} 张，重发编码 {} 个",
            sourceId, targetId, rskuStats.get("migratedCount"), movedVariantCount, movedImageCount, reissuedCodes);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceRspuId", sourceId);
        result.put("targetRspuId", targetId);
        result.put("changedFields", changedFields);
        result.put("movedVariantCount", movedVariantCount);
        result.putAll(rskuStats);
        result.put("movedImageCount", movedImageCount);
        result.put("reissuedRskuCodes", reissuedCodes);
        result.put("message", "合并完成，副本已移入回收站");
        return result;
    }

    /**
     * 查询产品的 pending 疑似同款配对（合并候选，M4 前端合并向导数据源）。
     *
     * @param rspuId RSPU ID
     * @return 配对列表（含命中产品的编码与品名）
     */
    public List<Map<String, Object>> listPendingSuspects(String rspuId) {
        if (rspuMapper.selectById(rspuId) == null) {
            throw new ResourceNotFoundException("产品不存在: " + rspuId);
        }
        List<RspuDuplicateSuspect> suspects = duplicateSuspectMapper.selectList(
            new QueryWrapper<RspuDuplicateSuspect>()
                .eq("rspu_id", rspuId)
                .eq("status", "pending")
                .orderByDesc("similarity"));
        List<Map<String, Object>> result = new ArrayList<>();
        for (RspuDuplicateSuspect suspect : suspects) {
            RspuMaster matched = rspuMapper.selectById(suspect.getMatchedRspuId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("suspectId", suspect.getSuspectId());
            item.put("matchedRspuId", suspect.getMatchedRspuId());
            item.put("matchedRspuCode", matched != null ? matched.getRspuCode() : null);
            item.put("matchedProductName", matched != null ? matched.getProductName() : null);
            item.put("similarity", suspect.getSimilarity());
            item.put("createdAt", suspect.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    // ==================== 内部步骤 ====================

    /** 字段归并：仅补空缺 + takeSourceFields 覆盖，返回实际变更的字段名列表。 */
    private List<String> applyFieldMerge(RspuMaster source, RspuMaster target, List<String> takeSourceFields) {
        List<String> changed = new ArrayList<>();
        Set<String> takeSource = new LinkedHashSet<>(takeSourceFields);
        mergeStringField(source.getProductName(), target.getProductName(), takeSource.contains("productName"),
            v -> target.setProductName(v), changed, "productName");
        mergeStringField(source.getDescription(), target.getDescription(), takeSource.contains("description"),
            v -> target.setDescription(v), changed, "description");
        mergeStringField(source.getColorPrimaryName(), target.getColorPrimaryName(), takeSource.contains("colorPrimaryName"),
            v -> target.setColorPrimaryName(v), changed, "colorPrimaryName");
        mergeStringField(source.getMaterialTags(), target.getMaterialTags(), takeSource.contains("materialTags"),
            v -> target.setMaterialTags(v), changed, "materialTags");
        mergeStringField(source.getFabricTags(), target.getFabricTags(), takeSource.contains("fabricTags"),
            v -> target.setFabricTags(v), changed, "fabricTags");
        mergeStringField(source.getSixDimTags(), target.getSixDimTags(), takeSource.contains("sixDimTags"),
            v -> target.setSixDimTags(v), changed, "sixDimTags");
        mergeStringField(source.getReferencePriceBand(), target.getReferencePriceBand(), takeSource.contains("referencePriceBand"),
            v -> target.setReferencePriceBand(v), changed, "referencePriceBand");
        mergeStringField(source.getProductLevel(), target.getProductLevel(), takeSource.contains("productLevel"),
            v -> target.setProductLevel(v), changed, "productLevel");
        mergeStringField(source.getKeySpecs(), target.getKeySpecs(), takeSource.contains("keySpecs"),
            v -> target.setKeySpecs(v), changed, "keySpecs");
        if (source.getRetailPrice() != null && (target.getRetailPrice() == null || takeSource.contains("retailPrice"))) {
            target.setRetailPrice(source.getRetailPrice());
            changed.add("retailPrice");
        }
        if (source.getWarrantyYears() != null && (target.getWarrantyYears() == null || takeSource.contains("warrantyYears"))) {
            target.setWarrantyYears(source.getWarrantyYears());
            changed.add("warrantyYears");
        }
        return changed;
    }

    private void mergeStringField(String sourceValue, String targetValue, boolean forceTakeSource,
                                  java.util.function.Consumer<String> setter, List<String> changed, String fieldName) {
        if (!StringUtils.hasText(sourceValue)) {
            return;
        }
        if (forceTakeSource || !StringUtils.hasText(targetValue)) {
            setter.accept(sourceValue);
            changed.add(fieldName);
        }
    }

    /** 风格/场景并集重写目标关联表（目标行保持 is_primary，副本码补齐为辅风格），返回场景并集码列表。 */
    private List<String> mergeStyleAssociations(String sourceId, RspuMaster target, String operator) {
        String targetId = target.getRspuId();
        List<RspuStyle> targetStyles = rspuStyleMapper.selectList(
            new QueryWrapper<RspuStyle>().eq("rspu_id", targetId));
        List<RspuStyle> sourceStyles = rspuStyleMapper.selectList(
            new QueryWrapper<RspuStyle>().eq("rspu_id", sourceId));
        Set<String> styleCodes = new LinkedHashSet<>();
        targetStyles.forEach(s -> styleCodes.add(s.getStyleCode()));
        List<RspuStyle> addedStyles = new ArrayList<>();
        for (RspuStyle s : sourceStyles) {
            if (styleCodes.add(s.getStyleCode())) {
                addedStyles.add(s);
            }
        }
        if (!addedStyles.isEmpty()) {
            rspuStyleMapper.delete(new QueryWrapper<RspuStyle>().eq("rspu_id", targetId));
            for (RspuStyle s : targetStyles) {
                rspuStyleMapper.insert(s);
            }
            for (RspuStyle s : addedStyles) {
                RspuStyle row = new RspuStyle();
                row.setRspuId(targetId);
                row.setStyleCode(s.getStyleCode());
                row.setIsPrimary(false);
                rspuStyleMapper.insert(row);
            }
            auditLogService.logUpdate("rspu_style", targetId,
                targetStyles.stream().map(RspuStyle::getStyleCode).toList(), styleCodes, operator);
        }

        List<RspuScene> targetScenes = rspuSceneMapper.selectList(
            new QueryWrapper<RspuScene>().eq("rspu_id", targetId));
        List<RspuScene> sourceScenes = rspuSceneMapper.selectList(
            new QueryWrapper<RspuScene>().eq("rspu_id", sourceId));
        Set<String> sceneCodes = new LinkedHashSet<>();
        targetScenes.forEach(s -> sceneCodes.add(s.getSceneCode()));
        List<RspuScene> addedScenes = new ArrayList<>();
        for (RspuScene s : sourceScenes) {
            if (sceneCodes.add(s.getSceneCode())) {
                addedScenes.add(s);
            }
        }
        if (!addedScenes.isEmpty()) {
            rspuSceneMapper.delete(new QueryWrapper<RspuScene>().eq("rspu_id", targetId));
            for (RspuScene s : targetScenes) {
                rspuSceneMapper.insert(s);
            }
            for (RspuScene s : addedScenes) {
                RspuScene row = new RspuScene();
                row.setRspuId(targetId);
                row.setSceneCode(s.getSceneCode());
                rspuSceneMapper.insert(row);
            }
            auditLogService.logUpdate("rspu_scene", targetId,
                targetScenes.stream().map(RspuScene::getSceneCode).toList(), sceneCodes, operator);
        }
        return List.copyOf(sceneCodes);
    }

    /** 变体映射：按 uk_variant_attrs 的 COALESCE key 比对；命中填映射、未命中改挂，返回改挂数量。 */
    private int migrateVariants(String sourceId, String targetId, Map<String, String> variantIdMap) {
        List<RspuVariant> targetVariants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", targetId));
        Map<String, String> targetKeyMap = new HashMap<>();
        for (RspuVariant v : targetVariants) {
            targetKeyMap.put(variantKey(v), v.getVariantId());
        }
        int moved = 0;
        List<RspuVariant> sourceVariants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", sourceId));
        for (RspuVariant v : sourceVariants) {
            String hit = targetKeyMap.get(variantKey(v));
            if (hit != null) {
                // 同 key 变体已存在：RSKU 改指目标变体，副本变体留在副本下随软删级联清理
                variantIdMap.put(v.getVariantId(), hit);
            } else {
                // 目标无此属性组合：变体整体改挂（variant_id 主键不变，RSKU/容量行无需动）
                v.setRspuId(targetId);
                v.setUpdatedAt(LocalDateTime.now());
                rspuVariantMapper.updateById(v);
                variantIdMap.put(v.getVariantId(), v.getVariantId());
                moved++;
            }
        }
        return moved;
    }

    /** uk_variant_attrs 同口径的变体属性 key：COALESCE(size_code,size_text,'')等三段拼接。 */
    private String variantKey(RspuVariant v) {
        return coalesce(v.getSizeCode(), v.getSizeText()) + "|"
            + coalesce(v.getColorCode(), v.getColorText()) + "|"
            + coalesce(v.getMaterialCode(), v.getMaterialText());
    }

    private String coalesce(String a, String b) {
        return a != null ? a : (b != null ? b : "");
    }

    /**
     * RSKU 迁移：先两阶段（规划 + 校验冲突裁决齐整）后执行。
     * 冲突判定 = 目标已有同 (variant_id, factory_code) 未删行（含 variant_id 为 NULL 的业务判重，
     * idx_rsku_unique 的 PG NULL 语义不覆盖该情形）。
     */
    private Map<String, Object> migrateRskus(String sourceId, String targetId, Map<String, String> variantIdMap,
                                             Map<String, String> resolutions, String operator) {
        List<RskuSupply> sourceRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", sourceId));
        List<RskuSupply> targetRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", targetId));
        Map<String, RskuSupply> targetByVariantFactory = new HashMap<>();
        for (RskuSupply r : targetRskus) {
            targetByVariantFactory.put(rskuConflictKey(r.getVariantId(), r.getFactoryCode()), r);
        }

        // 阶段 1：规划 + 校验冲突裁决
        record Plan(RskuSupply rsku, String newVariantId, RskuSupply conflict, String resolution) { }
        List<Plan> plans = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        Map<String, String> resolutionMap = resolutions == null ? Map.of() : resolutions;
        for (RskuSupply r : sourceRskus) {
            String newVariantId = r.getVariantId() == null ? null
                : variantIdMap.getOrDefault(r.getVariantId(), r.getVariantId());
            RskuSupply conflict = targetByVariantFactory.get(rskuConflictKey(newVariantId, r.getFactoryCode()));
            String resolution = conflict == null ? null : resolutionMap.get(r.getRskuId());
            if (conflict != null && resolution == null) {
                unresolved.add(r.getRskuId() + "(" + r.getFactoryCode() + " ↔ 目标 "
                    + conflict.getRskuId() + ")");
            }
            if (resolution != null && !"keepSource".equals(resolution) && !"keepTarget".equals(resolution)) {
                throw new BusinessException("无效的 RSKU 冲突裁决: " + resolution + "，仅支持 keepSource/keepTarget");
            }
            plans.add(new Plan(r, newVariantId, conflict, resolution));
        }
        if (!unresolved.isEmpty()) {
            throw new BusinessException("存在 " + unresolved.size()
                + " 条 RSKU 报价冲突（同变体同工厂），请逐条裁决后重试: " + String.join("、", unresolved));
        }

        // 阶段 2：执行
        List<String> migratedIds = new ArrayList<>();
        List<String> keepTargetDeleted = new ArrayList<>();
        List<String> keepSourceReplaced = new ArrayList<>();
        for (Plan plan : plans) {
            RskuSupply r = plan.rsku();
            if (plan.conflict() != null && "keepTarget".equals(plan.resolution())) {
                // 留目标报价：副本报价软删（价格历史随之封存）
                rskuSupplyMapper.deleteById(r.getRskuId());
                keepTargetDeleted.add(r.getRskuId());
                continue;
            }
            if (plan.conflict() != null) {
                // keepSource：目标旧报价软删，副本报价迁移补位
                rskuSupplyMapper.deleteById(plan.conflict().getRskuId());
                keepSourceReplaced.add(plan.conflict().getRskuId());
            }
            r.setRspuId(targetId);
            r.setVariantId(plan.newVariantId());
            // 编码含源 rspu_code 段，迁移后语义失效：置空由 backfillCodesByRspu 统一重发（旧码记审计）。
            // 注意必须走 UpdateWrapper.set——updateById 非空字段策略会跳过 null，rsku_code 置空不生效
            // （2026-09-12 合并冒烟实测坐实：迁移后旧码残留、重发 0 个）
            rskuSupplyMapper.update(null, new UpdateWrapper<RskuSupply>()
                .eq("rsku_id", r.getRskuId())
                .set("rspu_id", targetId)
                .set("variant_id", plan.newVariantId())
                .set("rsku_code", null)
                .set("updated_at", LocalDateTime.now()));
            migratedIds.add(r.getRskuId());
        }
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("migratedCount", migratedIds.size());
        stats.put("migratedRskuIds", migratedIds);
        stats.put("conflictKeepTargetDeleted", keepTargetDeleted);
        stats.put("conflictKeepSourceReplaced", keepSourceReplaced);
        if (!migratedIds.isEmpty() || !keepTargetDeleted.isEmpty()) {
            auditLogService.logUpdate("rsku_supply", targetId, null, Map.of(
                "action", "mergeRsku", "sourceRspuId", sourceId,
                "migrated", migratedIds, "keepTargetDeleted", keepTargetDeleted,
                "keepSourceReplaced", keepSourceReplaced), operator);
        }
        return stats;
    }

    private String rskuConflictKey(String variantId, String factoryCode) {
        return (variantId == null ? "" : variantId) + "|" + factoryCode;
    }

    /** 图片改挂目标（variant_id 随变体映射修正），返回改挂数量。 */
    private int migrateImages(String sourceId, String targetId, Map<String, String> variantIdMap) {
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", sourceId));
        for (ImageAssets image : images) {
            image.setRspuId(targetId);
            if (image.getVariantId() != null) {
                image.setVariantId(variantIdMap.getOrDefault(image.getVariantId(), image.getVariantId()));
            }
            imageAssetsMapper.updateById(image);
        }
        return images.size();
    }

    /** 引用表改指：先按约束去重再改指；搭配关系改指后清理自环与重复。 */
    private void repointReferences(String sourceId, String targetId) {
        // 业务凭证（含软删行，否则副本永久删除永被拦截）
        rspuMergeMapper.repointSchemeItems(sourceId, targetId);
        // 收藏/产品集：先按 (用户/产品集, 目标) 去重再改指
        rspuMergeMapper.deleteDuplicateFavorites(sourceId, targetId);
        rspuMergeMapper.repointFavorites(sourceId, targetId);
        rspuMergeMapper.deleteDuplicateCollectionItems(sourceId, targetId);
        rspuMergeMapper.repointCollectionItems(sourceId, targetId);
        // 工厂关联：UNIQUE(rspu_id, factory_code) + 单主供语义
        rspuMergeMapper.deleteDuplicateFactoryMappings(sourceId, targetId);
        rspuMergeMapper.repointFactoryMappings(sourceId, targetId);
        rspuMergeMapper.collapseDuplicatePrimaryMappings(targetId);
        // 识别历史/匹配反馈/风格匹配/方案候选归拢
        rspuMergeMapper.repointAiRecognitions(sourceId, targetId);
        rspuMergeMapper.repointMatchingFeedback(sourceId, targetId);
        rspuMergeMapper.repointMatchingFeedbackRecommended(sourceId, targetId);
        rspuMergeMapper.repointProductStyleMatch(sourceId, targetId);
        rspuMergeMapper.repointSchemeCandidates(sourceId, targetId);
        // 搭配关系双侧改指（实体走 @TableLogic，仅未删行）+ 自环/重复清理
        rspuMergeMapper.softDeleteSelfRelations();
        repointRelations(sourceId, targetId);
        rspuMergeMapper.softDeleteSelfRelations();
        rspuMergeMapper.softDeleteDuplicateRelations(targetId);
    }

    private void repointRelations(String sourceId, String targetId) {
        // anchor 侧（@TableLogic 自动过滤已删行）
        rspuRelationMapper.update(null, new UpdateWrapper<com.rsdp.entity.RspuRelation>()
            .eq("anchor_rspu_id", sourceId)
            .set("anchor_rspu_id", targetId)
            .set("updated_at", LocalDateTime.now()));
        // related 侧
        rspuRelationMapper.update(null, new UpdateWrapper<com.rsdp.entity.RspuRelation>()
            .eq("related_rspu_id", sourceId)
            .set("related_rspu_id", targetId)
            .set("updated_at", LocalDateTime.now()));
    }

    /** 疑似同款配对闭环：source↔target 配对置 merged，副本其余 pending 配对置 dismissed。 */
    private void closeSuspectPairs(String sourceId, String targetId, String operator) {
        LocalDateTime now = LocalDateTime.now();
        duplicateSuspectMapper.update(null, new UpdateWrapper<RspuDuplicateSuspect>()
            .eq("status", "pending")
            .and(w -> w
                .and(q -> q.eq("rspu_id", sourceId).eq("matched_rspu_id", targetId))
                .or(q -> q.eq("rspu_id", targetId).eq("matched_rspu_id", sourceId)))
            .set("status", "merged")
            .set("resolved_by", operator)
            .set("resolved_at", now));
        duplicateSuspectMapper.update(null, new UpdateWrapper<RspuDuplicateSuspect>()
            .eq("rspu_id", sourceId)
            .eq("status", "pending")
            .set("status", "dismissed")
            .set("resolved_by", operator)
            .set("resolved_at", now));
    }

    /** 归并字段快照（审计用）。 */
    private RspuMaster snapshotMergeableFields(RspuMaster rspu) {
        RspuMaster snapshot = new RspuMaster();
        snapshot.setRspuId(rspu.getRspuId());
        snapshot.setProductName(rspu.getProductName());
        snapshot.setDescription(rspu.getDescription());
        snapshot.setRetailPrice(rspu.getRetailPrice());
        snapshot.setColorPrimaryName(rspu.getColorPrimaryName());
        snapshot.setMaterialTags(rspu.getMaterialTags());
        snapshot.setFabricTags(rspu.getFabricTags());
        snapshot.setSixDimTags(rspu.getSixDimTags());
        snapshot.setReferencePriceBand(rspu.getReferencePriceBand());
        snapshot.setProductLevel(rspu.getProductLevel());
        snapshot.setWarrantyYears(rspu.getWarrantyYears());
        snapshot.setKeySpecs(rspu.getKeySpecs());
        return snapshot;
    }

    private String toJsonQuietly(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("场景并集码序列化失败，保留目标原值", e);
            return null;
        }
    }
}
