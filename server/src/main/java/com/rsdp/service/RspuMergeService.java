package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.request.RspuMergeRequest;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.RspuDuplicateSuspect;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
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

    /** 字段中文标签（预览接口返回给前端合并向导展示） */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
        Map.entry("productName", "品名"), Map.entry("description", "描述"), Map.entry("retailPrice", "零售参考价"),
        Map.entry("colorPrimaryName", "主色"), Map.entry("materialTags", "材质标签"), Map.entry("fabricTags", "面料标签"),
        Map.entry("sixDimTags", "六维标签"), Map.entry("referencePriceBand", "参考价格带"),
        Map.entry("productLevel", "产品等级"), Map.entry("warrantyYears", "质保年限"), Map.entry("keySpecs", "关键规格"));

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

    /** RSKU 迁移计划（合并执行与预览共用） */
    private record RskuPlan(RskuSupply rsku, String newVariantId, RskuSupply conflict, String resolution) { }

    /** RSKU 冲突明细（预览展示/未裁决报错共用） */
    private record RskuConflict(String rskuId, String factoryCode, String conflictRskuId) { }

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
        RspuMaster[] pair = loadAndValidatePair(sourceId, targetId);
        RspuMaster source = pair[0];
        RspuMaster target = pair[1];
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
        List<RspuVariant> variantMoves = planVariantMapping(sourceId, targetId, variantIdMap);
        int movedVariantCount = applyVariantMoves(variantMoves, targetId);

        // ---- 5. RSKU 迁移（先全量校验冲突裁决齐不齐，再执行；冲突未裁决整体拒绝） ----
        Map<String, String> resolutions = request.getRskuConflictResolutions() == null
            ? Map.of() : request.getRskuConflictResolutions();
        List<RskuPlan> plans = planRskuMigration(sourceId, targetId, variantIdMap, resolutions);
        List<RskuConflict> conflicts = findUnresolvedConflicts(plans);
        if (!conflicts.isEmpty()) {
            throw new BusinessException("存在 " + conflicts.size()
                + " 条 RSKU 报价冲突（同变体同工厂），请逐条裁决后重试: " + formatConflicts(conflicts));
        }
        Map<String, Object> rskuStats = executeRskuPlans(plans, sourceId, targetId, operator);

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
     * 合并预览（只读，M4 前端合并向导数据源）：字段差异 + 变体映射计划 + RSKU 冲突清单。
     * 守卫口径与正式合并一致（仅平台员工、双方存在且非识别中）。
     *
     * @param request 仅需 sourceRspuId/targetRspuId
     * @return 预览结果（fieldDiffs/conflicts/movedVariantCount/imageCount）
     */
    @Transactional(readOnly = true)
    public Map<String, Object> preview(RspuMergeRequest request) {
        String sourceId = request.getSourceRspuId().trim();
        String targetId = request.getTargetRspuId().trim();
        RspuMaster[] pair = loadAndValidatePair(sourceId, targetId);
        RspuMaster source = pair[0];
        RspuMaster target = pair[1];

        List<Map<String, Object>> fieldDiffs = new ArrayList<>();
        for (String field : MERGEABLE_FIELDS) {
            Object sourceValue = readField(source, field);
            Object targetValue = readField(target, field);
            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("field", field);
            diff.put("label", FIELD_LABELS.get(field));
            diff.put("sourceValue", sourceValue);
            diff.put("targetValue", targetValue);
            // 默认行为 = 仅补空缺：源有值且目标空缺时自动填入
            diff.put("willFill", hasValue(sourceValue) && !hasValue(targetValue));
            fieldDiffs.add(diff);
        }

        Map<String, String> variantIdMap = new HashMap<>();
        List<RspuVariant> variantMoves = planVariantMapping(sourceId, targetId, variantIdMap);
        List<RskuPlan> plans = planRskuMigration(sourceId, targetId, variantIdMap, Map.of());
        List<RskuConflict> conflicts = findUnresolvedConflicts(plans);
        int imageCount = imageAssetsMapper.selectCount(
            new QueryWrapper<ImageAssets>().eq("rspu_id", sourceId)).intValue();

        List<Map<String, Object>> conflictItems = new ArrayList<>();
        for (RskuConflict c : conflicts) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rskuId", c.rskuId());
            item.put("factoryCode", c.factoryCode());
            item.put("conflictRskuId", c.conflictRskuId());
            conflictItems.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceRspuId", sourceId);
        result.put("targetRspuId", targetId);
        result.put("fieldDiffs", fieldDiffs);
        result.put("conflicts", conflictItems);
        result.put("movedVariantCount", variantMoves.size());
        result.put("rskuCount", plans.size());
        result.put("imageCount", imageCount);
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

    // ==================== 守卫与规划（合并/预览共用） ====================

    /** 加载并校验合并配对：仅平台员工、source≠target、双方存在且非识别中。 */
    private RspuMaster[] loadAndValidatePair(String sourceId, String targetId) {
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
        return new RspuMaster[]{source, target};
    }

    /**
     * 变体映射规划（只读）：按 uk_variant_attrs 同 COALESCE key 比对，
     * 命中填映射、未命中加入待改挂清单。
     *
     * @param variantIdMap 输出：副本 variantId → 目标 variantId（改挂时为自身）
     * @return 待改挂到目标的副本变体列表
     */
    private List<RspuVariant> planVariantMapping(String sourceId, String targetId, Map<String, String> variantIdMap) {
        List<RspuVariant> targetVariants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", targetId));
        Map<String, String> targetKeyMap = new HashMap<>();
        for (RspuVariant v : targetVariants) {
            targetKeyMap.put(variantKey(v), v.getVariantId());
        }
        List<RspuVariant> moves = new ArrayList<>();
        List<RspuVariant> sourceVariants = rspuVariantMapper.selectList(
            new QueryWrapper<RspuVariant>().eq("rspu_id", sourceId));
        for (RspuVariant v : sourceVariants) {
            String hit = targetKeyMap.get(variantKey(v));
            if (hit != null) {
                // 同 key 变体已存在：RSKU 改指目标变体，副本变体留在副本下随软删级联清理
                variantIdMap.put(v.getVariantId(), hit);
            } else {
                // 目标无此属性组合：变体整体改挂（variant_id 主键不变，RSKU/容量行无需动）
                variantIdMap.put(v.getVariantId(), v.getVariantId());
                moves.add(v);
            }
        }
        return moves;
    }

    /** 应用变体改挂（写），返回改挂数量。 */
    private int applyVariantMoves(List<RspuVariant> moves, String targetId) {
        for (RspuVariant v : moves) {
            v.setRspuId(targetId);
            v.setUpdatedAt(LocalDateTime.now());
            rspuVariantMapper.updateById(v);
        }
        return moves.size();
    }

    /**
     * RSKU 迁移规划（只读）：计算每条副本 RSKU 的新变体与目标侧冲突，
     * 冲突判定 = 目标已有同 (variant_id, factory_code) 未删行（含 variant_id 为 NULL 的业务判重，
     * idx_rsku_unique 的 PG NULL 语义不覆盖该情形）。裁决值合法性在此校验。
     */
    private List<RskuPlan> planRskuMigration(String sourceId, String targetId, Map<String, String> variantIdMap,
                                             Map<String, String> resolutions) {
        List<RskuSupply> sourceRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", sourceId));
        List<RskuSupply> targetRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", targetId));
        Map<String, RskuSupply> targetByVariantFactory = new HashMap<>();
        for (RskuSupply r : targetRskus) {
            targetByVariantFactory.put(rskuConflictKey(r.getVariantId(), r.getFactoryCode()), r);
        }
        List<RskuPlan> plans = new ArrayList<>();
        for (RskuSupply r : sourceRskus) {
            String newVariantId = r.getVariantId() == null ? null
                : variantIdMap.getOrDefault(r.getVariantId(), r.getVariantId());
            RskuSupply conflict = targetByVariantFactory.get(rskuConflictKey(newVariantId, r.getFactoryCode()));
            String resolution = conflict == null ? null : resolutions.get(r.getRskuId());
            if (resolution != null && !"keepSource".equals(resolution) && !"keepTarget".equals(resolution)) {
                throw new BusinessException("无效的 RSKU 冲突裁决: " + resolution + "，仅支持 keepSource/keepTarget");
            }
            plans.add(new RskuPlan(r, newVariantId, conflict, resolution));
        }
        return plans;
    }

    /** 找出计划中未裁决的冲突（resolution 为空且 conflict 非空）。 */
    private List<RskuConflict> findUnresolvedConflicts(List<RskuPlan> plans) {
        List<RskuConflict> conflicts = new ArrayList<>();
        for (RskuPlan plan : plans) {
            if (plan.conflict() != null && plan.resolution() == null) {
                conflicts.add(new RskuConflict(plan.rsku().getRskuId(),
                    plan.rsku().getFactoryCode(), plan.conflict().getRskuId()));
            }
        }
        return conflicts;
    }

    private String formatConflicts(List<RskuConflict> conflicts) {
        List<String> items = new ArrayList<>();
        for (RskuConflict c : conflicts) {
            items.add(c.rskuId() + "(" + c.factoryCode() + " ↔ 目标 " + c.conflictRskuId() + ")");
        }
        return String.join("、", items);
    }

    // ==================== 执行步骤 ====================

    /** 执行 RSKU 迁移计划（阶段 2 写入；调用前必须确保冲突已全部裁决）。 */
    private Map<String, Object> executeRskuPlans(List<RskuPlan> plans, String sourceId, String targetId,
                                                 String operator) {
        List<String> migratedIds = new ArrayList<>();
        List<String> keepTargetDeleted = new ArrayList<>();
        List<String> keepSourceReplaced = new ArrayList<>();
        for (RskuPlan plan : plans) {
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

    /** 字段归并：仅补空缺 + takeSourceFields 覆盖，返回实际变更的字段名列表。 */
    private List<String> applyFieldMerge(RspuMaster source, RspuMaster target, List<String> takeSourceFields) {
        List<String> changed = new ArrayList<>();
        Set<String> takeSource = new LinkedHashSet<>(takeSourceFields);
        mergeStringField(source.getProductName(), target.getProductName(), takeSource.contains("productName"),
            target::setProductName, changed, "productName");
        mergeStringField(source.getDescription(), target.getDescription(), takeSource.contains("description"),
            target::setDescription, changed, "description");
        mergeStringField(source.getColorPrimaryName(), target.getColorPrimaryName(), takeSource.contains("colorPrimaryName"),
            target::setColorPrimaryName, changed, "colorPrimaryName");
        mergeStringField(source.getMaterialTags(), target.getMaterialTags(), takeSource.contains("materialTags"),
            target::setMaterialTags, changed, "materialTags");
        mergeStringField(source.getFabricTags(), target.getFabricTags(), takeSource.contains("fabricTags"),
            target::setFabricTags, changed, "fabricTags");
        mergeStringField(source.getSixDimTags(), target.getSixDimTags(), takeSource.contains("sixDimTags"),
            target::setSixDimTags, changed, "sixDimTags");
        mergeStringField(source.getReferencePriceBand(), target.getReferencePriceBand(), takeSource.contains("referencePriceBand"),
            target::setReferencePriceBand, changed, "referencePriceBand");
        mergeStringField(source.getProductLevel(), target.getProductLevel(), takeSource.contains("productLevel"),
            target::setProductLevel, changed, "productLevel");
        mergeStringField(source.getKeySpecs(), target.getKeySpecs(), takeSource.contains("keySpecs"),
            target::setKeySpecs, changed, "keySpecs");
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

    /** uk_variant_attrs 同口径的变体属性 key：COALESCE(size_code,size_text,'')等三段拼接。 */
    private String variantKey(RspuVariant v) {
        return coalesce(v.getSizeCode(), v.getSizeText()) + "|"
            + coalesce(v.getColorCode(), v.getColorText()) + "|"
            + coalesce(v.getMaterialCode(), v.getMaterialText());
    }

    private String coalesce(String a, String b) {
        return a != null ? a : (b != null ? b : "");
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
        repointRelations(sourceId, targetId);
        rspuMergeMapper.softDeleteSelfRelations();
        rspuMergeMapper.softDeleteDuplicateRelations(targetId);
    }

    private void repointRelations(String sourceId, String targetId) {
        // anchor 侧（@TableLogic 自动过滤已删行）
        rspuRelationMapper.update(null, new UpdateWrapper<RspuRelation>()
            .eq("anchor_rspu_id", sourceId)
            .set("anchor_rspu_id", targetId)
            .set("updated_at", LocalDateTime.now()));
        // related 侧
        rspuRelationMapper.update(null, new UpdateWrapper<RspuRelation>()
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

    // ==================== 预览辅助 ====================

    /** 读取白名单字段值（预览展示）。 */
    private Object readField(RspuMaster rspu, String field) {
        return switch (field) {
            case "productName" -> rspu.getProductName();
            case "description" -> rspu.getDescription();
            case "retailPrice" -> rspu.getRetailPrice();
            case "colorPrimaryName" -> rspu.getColorPrimaryName();
            case "materialTags" -> rspu.getMaterialTags();
            case "fabricTags" -> rspu.getFabricTags();
            case "sixDimTags" -> rspu.getSixDimTags();
            case "referencePriceBand" -> rspu.getReferencePriceBand();
            case "productLevel" -> rspu.getProductLevel();
            case "warrantyYears" -> rspu.getWarrantyYears();
            case "keySpecs" -> rspu.getKeySpecs();
            default -> null;
        };
    }

    private boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        return !(value instanceof String s) || StringUtils.hasText(s);
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
