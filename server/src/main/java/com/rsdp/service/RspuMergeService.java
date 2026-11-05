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
import com.rsdp.util.ManualOverrideFields;
import com.rsdp.util.VariantKeys;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 同款产品合并服务（决策点②共享主档模型：平台归一去重的执行器）。
 *
 * <p>把重复副本 RSPU 合并到目标 RSPU：字段仅补空缺（可指定取副本值）、风格/场景并集、
 * 变体按 (尺寸,颜色,材质) key 自动映射或改挂、RSKU 迁移（冲突必须人工裁决）、图片改挂
 * （imageId 主键不变，pgvector 向量无需重建）、全部引用表改指（含 supplier_product_ref
 * 供应商身份：同身份键去重后改指目标，合并后副本厂型号再导入命中目标而非空坟）、双投影重算，
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
    private final com.rsdp.mapper.AuditLogMapper auditLogMapper;
    private final RspuRelationMapper rspuRelationMapper;
    private final AuditLogService auditLogService;
    private final RspuAssociationHelper associationHelper;
    private final RspuPriceSummaryService rspuPriceSummaryService;
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

        // ---- 2.1 v3.3 §34.1（P0-D）：Merge 必须合并 manual_override_fields；
        //     同一字段两侧均活跃 → 活跃冲突进入人工治理（阻断合并，不得静默丢弃） ----
        var marksMerge = ManualOverrideFields.merge(
            target.getManualOverrideFields(), source.getManualOverrideFields());
        if (marksMerge.hasConflicts()) {
            throw new BusinessException("副本与目标存在同一字段的活跃人工修正标记冲突，需先人工治理: "
                + marksMerge.conflicts());
        }
        if (!marksMerge.mergedJson().equals(
            ManualOverrideFields.canonical(target.getManualOverrideFields()))) {
            target.setManualOverrideFields(marksMerge.mergedJson());
        }

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
        // 只延迟两条归属组合外键；变体与所有子行迁完后立即恢复校验。
        rspuMergeMapper.deferVariantOwnershipConstraints();
        int movedVariantCount = applyVariantMoves(variantMoves, targetId);

        // ---- 4.1 §34.1：映射到目标既有变体的副本变体随副本软删，其 manual_override_fields
        //     并入目标变体；同字段两侧活跃 → 人工治理（阻断合并） ----
        mergeVariantOverrideMarks(variantIdMap);

        // ---- 5. RSKU 迁移（先全量校验冲突裁决齐不齐，再执行；冲突未裁决整体拒绝） ----
        Map<String, String> resolutions = request.getRskuConflictResolutions() == null
            ? Map.of() : request.getRskuConflictResolutions();
        List<RskuPlan> plans = planRskuMigration(sourceId, targetId, variantIdMap, resolutions);
        List<RskuConflict> conflicts = findUnresolvedConflicts(plans);
        if (!conflicts.isEmpty()) {
            throw new BusinessException("存在 " + conflicts.size()
                + " 条 RSKU 报价冲突（同变体同工厂同供应配置），请逐条裁决后重试: " + formatConflicts(conflicts));
        }
        Map<String, Object> rskuStats = executeRskuPlans(plans, sourceId, targetId, operator);

        // ---- 6. 编码处理（2026-10-14 编码规则：发放后永不变更）----
        // 迁移的 RSKU 保留原 rsku_code（编码是发放时刻的快照，合并不改码；
        // 历史无码行仍可由 AiRecognitionPersistenceService 的 backfill 链路补发）

        // ---- 7. 图片改挂（主键 imageId 不变 → pgvector 向量行无需动，检索经 JOIN 自动跟随） ----
        List<String> movedImageIds = migrateImages(sourceId, targetId, variantIdMap);
        int movedImageCount = movedImageIds.size();
        // 软删行仍受外键约束：跟随整体改挂的变体保留归属，避免提交时遗留旧 rspu_id。
        for (RspuVariant variant : variantMoves) {
            rspuMergeMapper.repointArchivedVariantRskus(sourceId, targetId, variant.getVariantId());
            rspuMergeMapper.repointArchivedVariantImages(sourceId, targetId, variant.getVariantId());
        }
        rspuMergeMapper.checkVariantOwnershipConstraints();

        // ---- 8. 引用表改指（先改指再删副本，deleteProduct 级联才不会误伤已迁数据） ----
        // 回滚记录（合并历史/取消合并）：改指前捕获各引用表行 ID，回滚按 ID 精确逆向
        List<Long> supplierRefIdsBefore = rspuMergeMapper.listSupplierRefIds(sourceId);
        List<Long> factoryMappingIdsBefore = rspuMergeMapper.listFactoryMappingIds(sourceId);
        List<String> anchorRelationIdsBefore = rspuMergeMapper.listAnchorRelationIds(sourceId);
        List<String> relatedRelationIdsBefore = rspuMergeMapper.listRelatedRelationIds(sourceId);
        SupplierRefMoves supplierRefMoves = repointReferences(sourceId, targetId);

        // ---- 9. 双投影重算（源副本上的 RSKU 已迁走/软删，目标新增迁移 RSKU） ----
        rspuPriceSummaryService.recalculate(sourceId);
        rspuPriceSummaryService.recalculate(targetId);

        // ---- 10. 疑似同款配对闭环：本配对 merged，副本其余 pending 配对一并 dismissed ----
        closeSuspectPairs(sourceId, targetId, operator);

        // ---- 11. 副本软删（复用既有 deleteProduct：级联清理残留变体/关系 + 审计 + 向量事件） ----
        productQueryService.deleteProduct(sourceId);

        // 回滚支持（合并历史 + 取消合并）：迁移明细全量落审计——目标字段旧值、
        // 变体映射、迁移 RSKU/图片/供应商身份/搭配关系/工厂关联的行 ID 清单
        Map<String, Object> targetFieldBefore = new LinkedHashMap<>();
        for (String field : changedFields) {
            targetFieldBefore.put(field, readField(targetOldSnapshot, field));
        }
        Map<String, Object> mergeAudit = new LinkedHashMap<>();
        mergeAudit.put("action", "merge");
        mergeAudit.put("sourceRspuId", sourceId);
        mergeAudit.put("sourceRspuCode", source.getRspuCode() == null ? "" : source.getRspuCode());
        mergeAudit.put("sourceProductName", source.getProductName() == null ? "" : source.getProductName());
        mergeAudit.put("changedFields", changedFields);
        mergeAudit.put("targetFieldBefore", targetFieldBefore);
        mergeAudit.put("rsku", rskuStats);
        mergeAudit.put("movedVariants", movedVariantCount);
        mergeAudit.put("movedVariantIds", variantMoves.stream().map(RspuVariant::getVariantId).toList());
        mergeAudit.put("variantIdMap", variantIdMap);
        mergeAudit.put("movedImages", movedImageCount);
        mergeAudit.put("movedImageIds", movedImageIds);
        mergeAudit.put("movedSupplierRefs", supplierRefMoves.moved());
        mergeAudit.put("removedDuplicateSupplierRefs", supplierRefMoves.removedDuplicate());
        mergeAudit.put("supplierRefIdsBefore", supplierRefIdsBefore);
        mergeAudit.put("factoryMappingIdsBefore", factoryMappingIdsBefore);
        mergeAudit.put("anchorRelationIdsBefore", anchorRelationIdsBefore);
        mergeAudit.put("relatedRelationIdsBefore", relatedRelationIdsBefore);
        auditLogService.logUpdate("rspu_master", targetId, null, mergeAudit, operator);
        log.info("同款合并完成：{} → {}，迁移 RSKU {} 条、变体 {} 条、图片 {} 张、供应商身份 {} 条",
            sourceId, targetId, rskuStats.get("migratedCount"), movedVariantCount, movedImageCount,
            supplierRefMoves.moved());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceRspuId", sourceId);
        result.put("targetRspuId", targetId);
        result.put("changedFields", changedFields);
        result.put("movedVariantCount", movedVariantCount);
        result.putAll(rskuStats);
        result.put("movedImageCount", movedImageCount);
        result.put("movedSupplierRefs", supplierRefMoves.moved());
        result.put("removedDuplicateSupplierRefs", supplierRefMoves.removedDuplicate());
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
        // 副本名下的供应商身份记录数（合并时将改指目标；同身份键撞目标的会被去重删除）
        result.put("supplierRefCount", rspuMergeMapper.countSupplierProductRefs(sourceId));
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

    /**
     * 全局待归类队列（归类工作台数据源）：所有 pending 疑似同款配对，
     * 按相似度降序，含双侧产品的编码与品名。
     *
     * @return 配对列表
     */
    public List<Map<String, Object>> listAllPendingSuspects() {
        List<RspuDuplicateSuspect> suspects = duplicateSuspectMapper.selectList(
            new QueryWrapper<RspuDuplicateSuspect>()
                .eq("status", "pending")
                .orderByDesc("similarity"));
        // 批量预取双侧产品 / 主图 / 价格投影（消除 N+1）
        List<String> ids = suspects.stream()
            .flatMap(su -> java.util.stream.Stream.of(su.getRspuId(), su.getMatchedRspuId()))
            .distinct().toList();
        Map<String, RspuMaster> productMap = ids.isEmpty() ? Map.of()
            : rspuMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        Map<String, String> primaryImageMap = ids.isEmpty() ? Map.of()
            : imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
                    .select("rspu_id", "image_id")
                    .in("rspu_id", ids).eq("is_primary", true))
                .stream().collect(Collectors.toMap(ImageAssets::getRspuId,
                    img -> "/api/v1/images/" + img.getImageId(), (a, b) -> a));
        Map<String, com.rsdp.entity.RspuPriceSummary> summaryMap = ids.isEmpty() ? Map.of()
            : rspuPriceSummaryService.batchSummaries(ids);

        List<Map<String, Object>> result = new ArrayList<>();
        for (RspuDuplicateSuspect suspect : suspects) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("suspectId", suspect.getSuspectId());
            item.put("sideA", buildSuspectSide(suspect.getRspuId(), productMap, primaryImageMap, summaryMap));
            item.put("sideB", buildSuspectSide(suspect.getMatchedRspuId(), productMap, primaryImageMap, summaryMap));
            item.put("similarity", suspect.getSimilarity());
            item.put("createdAt", suspect.getCreatedAt());
            result.add(item);
        }
        return result;
    }

    /**
     * 疑似同款组聚合（归类工作台 V2）：对 pending 配对做并查集连通分组——
     * 多个产品互为同款（A↔B、B↔C）时聚成一组，组内一键合并到选定目标。
     *
     * @return 同款组列表（按组内最高相似度降序），每组含全部成员的富信息与最高相似度
     */
    public List<Map<String, Object>> listSuspectClusters() {
        List<RspuDuplicateSuspect> suspects = duplicateSuspectMapper.selectList(
            new QueryWrapper<RspuDuplicateSuspect>().eq("status", "pending"));
        if (suspects.isEmpty()) {
            return List.of();
        }
        // 并查集：同款关系是连通语义（A 像 B、B 像 C → ABC 一组）
        Map<String, String> parent = new HashMap<>();
        java.util.function.Function<String, String> find = new java.util.function.Function<>() {
            @Override
            public String apply(String x) {
                String p = parent.getOrDefault(x, x);
                if (!p.equals(x)) {
                    p = apply(p);
                    parent.put(x, p);
                }
                return p;
            }
        };
        for (RspuDuplicateSuspect suspect : suspects) {
            parent.putIfAbsent(suspect.getRspuId(), suspect.getRspuId());
            parent.putIfAbsent(suspect.getMatchedRspuId(), suspect.getMatchedRspuId());
            String pa = find.apply(suspect.getRspuId());
            String pb = find.apply(suspect.getMatchedRspuId());
            if (!pa.equals(pb)) {
                parent.put(pa, pb);
            }
        }
        Map<String, List<RspuDuplicateSuspect>> clusterPairs = new LinkedHashMap<>();
        Map<String, Set<String>> clusterMembers = new LinkedHashMap<>();
        for (RspuDuplicateSuspect suspect : suspects) {
            String root = find.apply(suspect.getRspuId());
            clusterPairs.computeIfAbsent(root, k -> new ArrayList<>()).add(suspect);
            clusterMembers.computeIfAbsent(root, k -> new LinkedHashSet<>()).add(suspect.getRspuId());
            clusterMembers.get(root).add(suspect.getMatchedRspuId());
        }

        List<String> allIds = clusterMembers.values().stream()
            .flatMap(Set::stream).distinct().toList();
        Map<String, RspuMaster> productMap = rspuMapper.selectBatchIds(allIds).stream()
            .collect(Collectors.toMap(RspuMaster::getRspuId, r -> r));
        Map<String, String> primaryImageMap = imageAssetsMapper.selectList(new QueryWrapper<ImageAssets>()
                .select("rspu_id", "image_id").in("rspu_id", allIds).eq("is_primary", true))
            .stream().collect(Collectors.toMap(ImageAssets::getRspuId,
                img -> "/api/v1/images/" + img.getImageId(), (a, b) -> a));
        Map<String, com.rsdp.entity.RspuPriceSummary> summaryMap = rspuPriceSummaryService.batchSummaries(allIds);

        List<Map<String, Object>> clusters = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : clusterMembers.entrySet()) {
            Map<String, Object> cluster = new LinkedHashMap<>();
            List<Map<String, Object>> members = entry.getValue().stream()
                .map(id -> buildSuspectSide(id, productMap, primaryImageMap, summaryMap))
                .toList();
            double maxSimilarity = clusterPairs.get(entry.getKey()).stream()
                .mapToDouble(su -> su.getSimilarity().doubleValue()).max().orElse(0);
            cluster.put("clusterKey", entry.getKey());
            cluster.put("members", members);
            cluster.put("memberCount", members.size());
            cluster.put("maxSimilarity", maxSimilarity);
            cluster.put("pairCount", clusterPairs.get(entry.getKey()).size());
            // 组内配对相似度明细（前端计算"各成员对所选基准的相似度"排序用）
            List<Map<String, Object>> pairDetails = new ArrayList<>();
            for (RspuDuplicateSuspect su : clusterPairs.get(entry.getKey())) {
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("rspuId", su.getRspuId());
                pair.put("matchedRspuId", su.getMatchedRspuId());
                pair.put("similarity", su.getSimilarity());
                pairDetails.add(pair);
            }
            cluster.put("pairs", pairDetails);
            clusters.add(cluster);
        }
        clusters.sort((a, b) -> Double.compare((Double) b.get("maxSimilarity"), (Double) a.get("maxSimilarity")));
        return clusters;
    }

    /** 构建配对一侧的产品信息（图片/编码/品名/品类/风格/主色/价格投影/报价数）。 */
    private Map<String, Object> buildSuspectSide(String rspuId, Map<String, RspuMaster> productMap,
                                                 Map<String, String> primaryImageMap,
                                                 Map<String, com.rsdp.entity.RspuPriceSummary> summaryMap) {
        RspuMaster product = productMap.get(rspuId);
        com.rsdp.entity.RspuPriceSummary summary = summaryMap.get(rspuId);
        Map<String, Object> side = new LinkedHashMap<>();
        side.put("rspuId", rspuId);
        side.put("rspuCode", product != null ? product.getRspuCode() : null);
        side.put("productName", product != null ? product.getProductName() : null);
        side.put("categoryPath", product != null ? product.getCategoryPath() : null);
        side.put("positioningLabel", product != null ? product.getPositioningLabel() : null);
        side.put("colorPrimaryName", product != null ? product.getColorPrimaryName() : null);
        side.put("primaryImageUrl", primaryImageMap.get(rspuId));
        side.put("retailPrice", product != null ? product.getRetailPrice() : null);
        side.put("minFactoryPrice", summary != null ? summary.getMinFactoryPrice() : null);
        side.put("activeRskuCount", summary != null ? summary.getActiveRskuCount() : 0);
        return side;
    }

    /**
     * 人工排除疑似同款配对（确认不是同款）：状态置 dismissed。
     * 仅平台员工可执行（与合并同一守卫口径）。
     *
     * @param suspectId 配对记录 ID
     */
    public void dismissSuspect(Long suspectId) {
        if (!SecurityOperatorContext.isPlatformStaff()) {
            throw new BusinessException("同款配对排除仅平台运营人员可执行");
        }
        String operator = SecurityOperatorContext.currentUsername();
        int updated = duplicateSuspectMapper.update(null, new UpdateWrapper<RspuDuplicateSuspect>()
            .eq("suspect_id", suspectId)
            .eq("status", "pending")
            .set("status", "dismissed")
            .set("resolved_by", operator)
            .set("resolved_at", LocalDateTime.now()));
        if (updated == 0) {
            throw new ResourceNotFoundException("疑似同款配对不存在或已处理: " + suspectId);
        }
        auditLogService.logUpdate("rspu_duplicate_suspect", String.valueOf(suspectId),
            Map.of("status", "pending"), Map.of("status", "dismissed"), operator);
    }

    /**
     * 变体归并（同一 RSPU 下两个语义重复变体的收口，归类工作台配套能力）：
     * 源变体的 RSKU 报价（同工厂同供应配置冲突必须人工裁决）与图片改挂目标变体，
     * 人工修正标记并集（同字段两侧活跃冲突阻断），源变体软删，价格投影重算。
     *
     * @param rspuId  所属产品 ID（两个变体必须同属该产品）
     * @param request 源/目标变体 + RSKU 冲突裁决（副本 rskuId → keepSource/keepTarget）
     * @return 归并结果统计（migratedRskuCount/movedImageCount/冲突处置清单）
     */
    @Transactional
    public Map<String, Object> mergeVariants(String rspuId, com.rsdp.dto.request.VariantMergeRequest request) {
        String operator = SecurityOperatorContext.currentUsername();
        if (!SecurityOperatorContext.isPlatformStaff()) {
            throw new BusinessException("变体归并仅平台运营人员可执行");
        }
        String sourceId = request.getSourceVariantId().trim();
        String targetId = request.getTargetVariantId().trim();
        if (sourceId.equals(targetId)) {
            throw new BusinessException("源变体与目标变体不能是同一个");
        }
        RspuVariant source = rspuVariantMapper.selectById(sourceId);
        RspuVariant target = rspuVariantMapper.selectById(targetId);
        if (source == null || target == null) {
            throw new ResourceNotFoundException("变体不存在: " + (source == null ? sourceId : targetId));
        }
        if (!rspuId.equals(source.getRspuId()) || !rspuId.equals(target.getRspuId())) {
            throw new BusinessException("变体归并仅支持同一产品下的变体");
        }

        // 人工修正标记并集：同字段两侧均活跃 → 阻断（与 RSPU 合并同语义，不静默丢弃）
        var marksMerge = ManualOverrideFields.merge(
            target.getManualOverrideFields(), source.getManualOverrideFields());
        if (marksMerge.hasConflicts()) {
            throw new BusinessException("源变体与目标变体存在同字段活跃人工修正标记冲突，需先人工治理: "
                + marksMerge.conflicts());
        }

        // RSKU 迁移规划：冲突 = 目标变体已有同（工厂 + 供应配置）未删报价
        List<RskuSupply> sourceRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("variant_id", sourceId));
        List<RskuSupply> targetRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("variant_id", targetId));
        Map<String, RskuSupply> targetByKey = new HashMap<>();
        for (RskuSupply t : targetRskus) {
            targetByKey.put(t.getFactoryCode() + "|" + t.getSupplierConfigurationKey(), t);
        }
        Map<String, String> resolutions = request.getRskuConflictResolutions() == null
            ? Map.of() : request.getRskuConflictResolutions();
        List<String> unresolved = new ArrayList<>();
        for (RskuSupply r : sourceRskus) {
            RskuSupply conflict = targetByKey.get(r.getFactoryCode() + "|" + r.getSupplierConfigurationKey());
            if (conflict != null) {
                String resolution = resolutions.get(r.getRskuId());
                if (!"keepSource".equals(resolution) && !"keepTarget".equals(resolution)) {
                    unresolved.add(r.getRskuId() + "（与 " + conflict.getRskuId() + " 同厂同配置冲突）");
                }
            }
        }
        if (!unresolved.isEmpty()) {
            throw new BusinessException("存在 " + unresolved.size()
                + " 条 RSKU 报价冲突（归并后同工厂同供应配置），请逐条裁决后重试: " + String.join(", ", unresolved));
        }

        List<String> migrated = new ArrayList<>();
        List<String> keepTargetDeleted = new ArrayList<>();
        List<String> keepSourceReplaced = new ArrayList<>();
        for (RskuSupply r : sourceRskus) {
            RskuSupply conflict = targetByKey.get(r.getFactoryCode() + "|" + r.getSupplierConfigurationKey());
            if (conflict != null && "keepTarget".equals(resolutions.get(r.getRskuId()))) {
                rskuSupplyMapper.deleteById(r.getRskuId());
                keepTargetDeleted.add(r.getRskuId());
                continue;
            }
            if (conflict != null) {
                // keepSource：目标旧报价软删，源报价迁移补位
                rskuSupplyMapper.deleteById(conflict.getRskuId());
                keepSourceReplaced.add(conflict.getRskuId());
            }
            // 只改 variant_id（rspu_id 不变，组合 FK (rspu_id, variant_id) 天然满足）
            rskuSupplyMapper.update(null, new UpdateWrapper<RskuSupply>()
                .eq("rsku_id", r.getRskuId())
                .set("variant_id", targetId)
                .set("updated_at", LocalDateTime.now()));
            migrated.add(r.getRskuId());
        }

        // 图片改挂（@TableLogic 口径：仅未删除行；已删图片随源变体留档，不影响展示与检索）
        int movedImages = imageAssetsMapper.update(null, new UpdateWrapper<ImageAssets>()
            .eq("variant_id", sourceId)
            .set("variant_id", targetId));

        // 人工修正标记并入目标变体
        if (!marksMerge.mergedJson().equals(
            ManualOverrideFields.canonical(target.getManualOverrideFields()))) {
            rspuVariantMapper.update(null, new UpdateWrapper<RspuVariant>()
                .eq("variant_id", targetId)
                .set("manual_override_fields", marksMerge.mergedJson())
                .set("updated_at", LocalDateTime.now()));
        }

        // 源变体软删
        rspuVariantMapper.deleteById(sourceId);
        rspuPriceSummaryService.recalculate(rspuId);

        auditLogService.logUpdate("rspu_variant", targetId, null, Map.of(
            "action", "mergeVariant",
            "rspuId", rspuId,
            "sourceVariantId", sourceId,
            "migratedRskuIds", migrated,
            "conflictKeepTargetDeleted", keepTargetDeleted,
            "conflictKeepSourceReplaced", keepSourceReplaced,
            "movedImageCount", movedImages), operator);
        log.info("变体归并完成：{} → {}（{}），迁移 RSKU {} 条、图片 {} 张",
            sourceId, targetId, rspuId, migrated.size(), movedImages);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceVariantId", sourceId);
        result.put("targetVariantId", targetId);
        result.put("migratedRskuCount", migrated.size());
        result.put("migratedRskuIds", migrated);
        result.put("conflictKeepTargetDeleted", keepTargetDeleted);
        result.put("conflictKeepSourceReplaced", keepSourceReplaced);
        result.put("movedImageCount", movedImages);
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

    /**
     * 合并「映射到目标既有变体」的副本变体人工修正标记（v3.3 §34.1，P0-D）。
     *
     * <p>改挂变体（variant_id 不变）标记随行保留，无需处理；只有 source≠target 的
     * 映射对（副本变体将随副本软删）需要把标记并入目标变体。同字段两侧均活跃 →
     * 人工治理（抛错阻断合并，事务整体回滚，不得静默丢弃）。</p>
     */
    private void mergeVariantOverrideMarks(Map<String, String> variantIdMap) {
        for (Map.Entry<String, String> e : variantIdMap.entrySet()) {
            if (e.getKey().equals(e.getValue())) {
                continue;
            }
            RspuVariant sourceVariant = rspuVariantMapper.selectById(e.getKey());
            RspuVariant targetVariant = rspuVariantMapper.selectById(e.getValue());
            if (sourceVariant == null || targetVariant == null) {
                continue;
            }
            var marksMerge = ManualOverrideFields.merge(
                targetVariant.getManualOverrideFields(), sourceVariant.getManualOverrideFields());
            if (marksMerge.hasConflicts()) {
                throw new BusinessException("变体合并存在同字段活跃人工修正标记冲突，需先人工治理: "
                    + marksMerge.conflicts() + "，sourceVariantId=" + e.getKey());
            }
            if (!marksMerge.mergedJson().equals(
                ManualOverrideFields.canonical(targetVariant.getManualOverrideFields()))) {
                rspuVariantMapper.update(null, new UpdateWrapper<RspuVariant>()
                    .eq("variant_id", e.getValue())
                    .set("manual_override_fields", marksMerge.mergedJson())
                    .set("updated_at", LocalDateTime.now()));
            }
        }
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
     * 冲突判定 = 目标已有同 (variant_id, factory_code, supplier_configuration_key) 未删行。
     * 裁决值合法性在此校验；不同供应配置可并存。
     */
    private List<RskuPlan> planRskuMigration(String sourceId, String targetId, Map<String, String> variantIdMap,
                                             Map<String, String> resolutions) {
        List<RskuSupply> sourceRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", sourceId));
        List<RskuSupply> targetRskus = rskuSupplyMapper.selectList(
            new QueryWrapper<RskuSupply>().eq("rspu_id", targetId));
        Map<RskuIdentity, RskuSupply> targetByIdentity = new HashMap<>();
        for (RskuSupply r : targetRskus) {
            targetByIdentity.put(new RskuIdentity(r.getVariantId(), r.getFactoryCode(), r.getSupplierConfigurationKey()), r);
        }
        List<RskuPlan> plans = new ArrayList<>();
        for (RskuSupply r : sourceRskus) {
            String newVariantId = r.getVariantId() == null ? null
                : variantIdMap.getOrDefault(r.getVariantId(), r.getVariantId());
            RskuSupply conflict = targetByIdentity.get(new RskuIdentity(newVariantId, r.getFactoryCode(), r.getSupplierConfigurationKey()));
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
                // 留目标报价：副本报价软删（价格历史随之封存）；
                // §34.1：被弃副本行的 manual_override_fields 并入存续目标行，
                // 同字段两侧活跃 → 人工治理（阻断合并，事务回滚）
                var marksMerge = ManualOverrideFields.merge(
                    plan.conflict().getManualOverrideFields(), r.getManualOverrideFields());
                if (marksMerge.hasConflicts()) {
                    throw new BusinessException("RSKU 合并存在同字段活跃人工修正标记冲突（keepTarget），需先人工治理: "
                        + marksMerge.conflicts() + "，rskuId=" + r.getRskuId());
                }
                if (!marksMerge.mergedJson().equals(
                    ManualOverrideFields.canonical(plan.conflict().getManualOverrideFields()))) {
                    rskuSupplyMapper.update(null, new UpdateWrapper<RskuSupply>()
                        .eq("rsku_id", plan.conflict().getRskuId())
                        .set("manual_override_fields", marksMerge.mergedJson())
                        .set("updated_at", LocalDateTime.now()));
                }
                rskuSupplyMapper.deleteById(r.getRskuId());
                keepTargetDeleted.add(r.getRskuId());
                continue;
            }
            if (plan.conflict() != null) {
                // keepSource：目标旧报价软删，副本报价迁移补位
                rskuSupplyMapper.deleteById(plan.conflict().getRskuId());
                keepSourceReplaced.add(plan.conflict().getRskuId());
            }
            // 迁移只改归属（rspu_id/variant_id）：rsku_code 发放后永不变更，原样保留
            // （2026-10-14 编码规则）；注意必须走 UpdateWrapper.set——字段归属变更不受非空策略影响
            rskuSupplyMapper.update(null, new UpdateWrapper<RskuSupply>()
                .eq("rsku_id", r.getRskuId())
                .set("rspu_id", targetId)
                .set("variant_id", plan.newVariantId())
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
            List<RspuStyle> unionRows = new ArrayList<>(targetStyles);
            for (RspuStyle s : addedStyles) {
                RspuStyle row = new RspuStyle();
                row.setRspuId(targetId);
                row.setStyleCode(s.getStyleCode());
                row.setIsPrimary(false);
                unionRows.add(row);
            }
            // 汇总审计（2.3 口径：Map.of("styleCodes", 码列表)，helper 统一）
            associationHelper.replaceStyles(targetId, unionRows, operator, true);
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
            List<RspuScene> unionRows = new ArrayList<>(targetScenes);
            for (RspuScene s : addedScenes) {
                RspuScene row = new RspuScene();
                row.setRspuId(targetId);
                row.setSceneCode(s.getSceneCode());
                unionRows.add(row);
            }
            // 汇总审计（2.3 口径：Map.of("sceneCodes", 码列表)，helper 统一）
            associationHelper.replaceScenes(targetId, unionRows, operator, true);
        }
        return List.copyOf(sceneCodes);
    }

    /** uk_variant_attrs 同口径的变体属性 key（委托共享实现，4.3 批③）。 */
    private String variantKey(RspuVariant v) {
        return VariantKeys.attrKey(v);
    }

    /** 与供应记录唯一身份一致，避免字符串拼接碰撞及不同供应配置被误判冲突。 */
    private record RskuIdentity(String variantId, String factoryCode, String supplierConfigurationKey) { }

    /** 图片改挂目标（variant_id 随变体映射修正），返回改挂数量。 */
    private List<String> migrateImages(String sourceId, String targetId, Map<String, String> variantIdMap) {
        List<ImageAssets> images = imageAssetsMapper.selectList(
            new QueryWrapper<ImageAssets>().eq("rspu_id", sourceId));
        List<String> movedIds = new ArrayList<>();
        for (ImageAssets image : images) {
            image.setRspuId(targetId);
            if (image.getVariantId() != null) {
                image.setVariantId(variantIdMap.getOrDefault(image.getVariantId(), image.getVariantId()));
            }
            imageAssetsMapper.updateById(image);
            movedIds.add(image.getImageId());
        }
        return movedIds;
    }

    /** 引用表改指：先按约束去重再改指；搭配关系改指后清理自环与重复。 */
    /** 供应商身份迁移结果（改指目标 / 撞键去重条数）。 */
    private record SupplierRefMoves(int moved, int removedDuplicate) { }

    /**
     * 引用表改指目标。返回供应商身份迁移计数（合并审计与结果用）。
     *
     * <p>supplier_product_ref 必须先按身份键去重再改指：目标已有同
     * (factory_code, source_namespace, normalized_source_product_code) 记录时
     * 保留目标记录、删除副本记录（撞 uk_supplier_product_ref），其余改指目标——
     * 合并后副本厂型号再导入必须命中目标 RSPU，不得悬空指向已软删副本。</p>
     */
    private SupplierRefMoves repointReferences(String sourceId, String targetId) {
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
        rspuMergeMapper.repointProductStyleMatch(sourceId, targetId);
        rspuMergeMapper.repointSchemeCandidates(sourceId, targetId);
        // 搭配关系双侧改指（实体走 @TableLogic，仅未删行）+ 自环/重复清理
        repointRelations(sourceId, targetId);
        rspuMergeMapper.softDeleteSelfRelations();
        rspuMergeMapper.softDeleteDuplicateRelations(targetId);
        // 供应商源商品身份：先去重（撞 uk_supplier_product_ref 的副本记录物理删）再改指目标
        int removedDuplicateRefs = rspuMergeMapper.deleteDuplicateSupplierProductRefs(sourceId, targetId);
        int movedRefs = rspuMergeMapper.repointSupplierProductRefs(sourceId, targetId);
        return new SupplierRefMoves(movedRefs, removedDuplicateRefs);
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
    // ==================== 合并历史与回滚 ====================

    /**
     * 合并历史列表（工作台「合并历史」页）：audit_log 中 action=merge 的记录，
     * 新→旧排序，含副本/目标信息、迁移统计与是否已回滚标记。
     *
     * @return 合并历史列表
     */
    public List<Map<String, Object>> listMergeHistory() {
        List<RspuMergeMapper.MergeHistoryRow> entries = rspuMergeMapper.listMergeHistory();
        List<Map<String, Object>> result = new ArrayList<>();
        for (RspuMergeMapper.MergeHistoryRow entry : entries) {
            Map<String, Object> detail = parseAuditDetail(entry.getNewValueJson());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("mergeAuditId", entry.getId());
            item.put("sourceRspuId", detail.get("sourceRspuId"));
            item.put("sourceRspuCode", detail.get("sourceRspuCode"));
            item.put("sourceProductName", detail.get("sourceProductName"));
            item.put("targetRspuId", entry.getRecordId());
            item.put("changedFields", detail.get("changedFields"));
            item.put("movedVariants", detail.get("movedVariants"));
            item.put("movedImages", detail.get("movedImages"));
            Object rsku = detail.get("rsku");
            item.put("migratedRskuCount", rsku instanceof Map<?, ?> m ? m.get("migratedCount") : null);
            item.put("movedSupplierRefs", detail.get("movedSupplierRefs"));
            item.put("operator", entry.getOperator());
            item.put("createdAt", entry.getCreatedAt());
            item.put("rolledBack", rspuMergeMapper.countRollbacksForMerge(entry.getId()) > 0);
            result.add(item);
        }
        return result;
    }

    /**
     * 取消合并（回滚）：按合并时审计记录的迁移明细精确逆向——
     * 副本 RSPU 从回收站恢复，迁移的变体/报价/图片/供应商身份/搭配关系/工厂关联
     * 按行 ID 改挂回副本，目标被字段归并覆盖的值还原，双端价格投影重算。
     *
     * <p>回滚边界（有意不还原）：风格/场景并集保留在目标（并集元数据无害）；业务凭证
     * （方案明细/收藏/产品集）仍指向目标产品（合并语义上"该产品"的延续）；撞键删除的
     * 重复供应商身份/工厂关联行是物理删除，回滚不重建（目标侧已有同键记录）。</p>
     *
     * @param mergeAuditId 合并审计记录 ID
     * @return 回滚结果统计
     */
    @Transactional
    public Map<String, Object> rollbackMerge(long mergeAuditId) {
        String operator = SecurityOperatorContext.currentUsername();
        if (!SecurityOperatorContext.isPlatformStaff()) {
            throw new BusinessException("取消合并仅平台运营人员可执行");
        }
        com.rsdp.entity.AuditLog mergeEntry = auditLogMapper.selectById(mergeAuditId);
        if (mergeEntry == null || mergeEntry.getNewValue() == null
            || !"merge".equals(mergeEntry.getNewValue().get("action"))) {
            throw new ResourceNotFoundException("合并记录不存在: " + mergeAuditId);
        }
        if (rspuMergeMapper.countRollbacksForMerge(mergeAuditId) > 0) {
            throw new BusinessException("该合并已被回滚过，不能重复回滚");
        }
        Map<String, Object> detail = mergeEntry.getNewValue();
        String sourceId = String.valueOf(detail.get("sourceRspuId"));
        String targetId = mergeEntry.getRecordId();

        // 副本当前必须仍在回收站（彻底删除后无法回滚）
        RspuMaster source = rspuMapper.selectAnyById(sourceId);
        if (source == null) {
            throw new ResourceNotFoundException("副本产品不存在: " + sourceId);
        }
        if (source.getDeletedAt() == null) {
            throw new BusinessException("副本产品当前未处于回收站状态，无法回滚（可能已被恢复或有其他合并）");
        }

        List<String> movedVariantIds = stringList(detail.get("movedVariantIds"));
        Map<String, String> variantIdMap = stringMap(detail.get("variantIdMap"));
        List<String> migratedRskuIds = stringList(nestedList(detail.get("rsku"), "migratedRskuIds"));
        List<String> keepTargetDeletedIds = stringList(nestedList(detail.get("rsku"), "conflictKeepTargetDeleted"));
        List<String> keepSourceReplacedIds = stringList(nestedList(detail.get("rsku"), "conflictKeepSourceReplaced"));
        List<String> movedImageIds = stringList(detail.get("movedImageIds"));
        List<Long> supplierRefIds = longList(detail.get("supplierRefIdsBefore"));
        List<Long> factoryMappingIds = longList(detail.get("factoryMappingIdsBefore"));
        List<String> anchorRelationIds = stringList(detail.get("anchorRelationIdsBefore"));
        List<String> relatedRelationIds = stringList(detail.get("relatedRelationIdsBefore"));
        Map<String, Object> targetFieldBefore = detail.get("targetFieldBefore") instanceof Map<?, ?> m
            ? (Map<String, Object>) m : Map.of();

        // 变体归属组合外键（fk_rsku_rspu_variant）在迁移中间态暂缓，迁完立即校验
        rspuMergeMapper.deferVariantOwnershipConstraints();

        // 1. 副本 RSPU 从回收站恢复
        if (rspuMergeMapper.restoreRspuRow(sourceId) == 0) {
            throw new BusinessException("副本产品已被彻底删除，无法回滚: " + sourceId);
        }

        // 2. 目标被字段归并覆盖的值还原
        if (!targetFieldBefore.isEmpty()) {
            RspuMaster target = rspuMapper.selectById(targetId);
            if (target != null) {
                for (Map.Entry<String, Object> entry : targetFieldBefore.entrySet()) {
                    writeField(target, entry.getKey(), entry.getValue());
                }
                target.setUpdatedAt(LocalDateTime.now());
                rspuMapper.updateById(target);
            }
        }

        // 3. 变体：整体改挂的移回副本；映射到目标既有变体的副本变体（随副本软删过）恢复
        for (String variantId : movedVariantIds) {
            rspuMergeMapper.repointVariantTo(variantId, sourceId);
        }
        for (Map.Entry<String, String> entry : variantIdMap.entrySet()) {
            if (!entry.getKey().equals(entry.getValue())) {
                rspuMergeMapper.restoreVariantRow(entry.getKey());
            }
        }

        // 4. RSKU：迁移的按逆映射改挂回副本变体；冲突处置软删的双方报价恢复
        Map<String, String> inverseVariantMap = new HashMap<>();
        for (Map.Entry<String, String> entry : variantIdMap.entrySet()) {
            inverseVariantMap.put(entry.getValue(), entry.getKey());
        }
        for (String rskuId : migratedRskuIds) {
            RskuSupply rsku = rskuSupplyMapper.selectById(rskuId);
            if (rsku == null) {
                continue;
            }
            String originalVariantId = inverseVariantMap.getOrDefault(rsku.getVariantId(), rsku.getVariantId());
            rskuSupplyMapper.update(null, new UpdateWrapper<RskuSupply>()
                .eq("rsku_id", rskuId)
                .set("rspu_id", sourceId)
                .set("variant_id", originalVariantId)
                .set("updated_at", LocalDateTime.now()));
        }
        for (String rskuId : keepTargetDeletedIds) {
            rspuMergeMapper.restoreRskuRow(rskuId);
        }
        for (String rskuId : keepSourceReplacedIds) {
            rspuMergeMapper.restoreRskuRow(rskuId);
        }
        rspuMergeMapper.checkVariantOwnershipConstraints();

        // 5. 图片改挂回副本（变体归属按逆映射修正）
        for (String imageId : movedImageIds) {
            ImageAssets image = imageAssetsMapper.selectById(imageId);
            if (image == null) {
                continue;
            }
            String originalVariantId = image.getVariantId() == null ? null
                : inverseVariantMap.getOrDefault(image.getVariantId(), image.getVariantId());
            rspuMergeMapper.repointImageTo(imageId, sourceId, originalVariantId);
        }

        // 6. 供应商身份改挂回副本（仅当前仍在目标上的行；撞键删除行不重建）
        for (Long refId : supplierRefIds) {
            rspuMergeMapper.repointSupplierRefToIfOn(refId, sourceId, targetId);
        }

        // 7. 搭配关系按捕获的双侧清单改回副本（自环/重复清理不动：那是合并语义的一部分）
        for (String relationId : anchorRelationIds) {
            rspuMergeMapper.repointRelationAnchorTo(relationId, sourceId);
        }
        for (String relationId : relatedRelationIds) {
            rspuMergeMapper.repointRelationRelatedTo(relationId, sourceId);
        }

        // 8. 工厂关联改挂回副本（仅当前仍在目标上的行）
        for (Long mappingId : factoryMappingIds) {
            rspuMergeMapper.repointFactoryMappingToIfOn(mappingId, sourceId, targetId);
        }

        // 9. 双端价格投影重算
        rspuPriceSummaryService.recalculate(sourceId);
        rspuPriceSummaryService.recalculate(targetId);

        // 10. 回滚审计（含防重复回滚标记）
        Map<String, Object> rollbackAudit = new LinkedHashMap<>();
        rollbackAudit.put("action", "merge_rollback");
        rollbackAudit.put("mergeAuditId", mergeAuditId);
        rollbackAudit.put("sourceRspuId", sourceId);
        rollbackAudit.put("restoredVariants", movedVariantIds.size() + variantIdMap.size());
        rollbackAudit.put("restoredRskus", migratedRskuIds.size());
        rollbackAudit.put("restoredImages", movedImageIds.size());
        rollbackAudit.put("restoredSupplierRefs", supplierRefIds.size());
        auditLogService.logUpdate("rspu_master", targetId, null, rollbackAudit, operator);
        log.info("合并回滚完成：mergeAuditId={}，副本 {} 已从 {} 拆出恢复", mergeAuditId, sourceId, targetId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mergeAuditId", mergeAuditId);
        result.put("sourceRspuId", sourceId);
        result.put("targetRspuId", targetId);
        result.put("message", "已取消合并，副本产品已从回收站恢复");
        return result;
    }

    /** 合并历史审计明细解析（jsonb 文本 → Map；解析失败按空明细降级，不阻断列表）。 */
    private Map<String, Object> parseAuditDetail(String newValueJson) {
        if (!StringUtils.hasText(newValueJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(newValueJson,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                });
        } catch (Exception e) {
            log.warn("合并历史审计明细解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    /** 审计明细 JSON 读取：字符串列表字段。 */
    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    /** 审计明细 JSON 读取：长整型列表字段。 */
    private List<Long> longList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(v -> Long.valueOf(String.valueOf(v))).toList();
    }

    /** 审计明细 JSON 读取：字符串键值映射字段。 */
    private Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
        }
        return result;
    }

    /** 审计明细 JSON 读取：嵌套 map 内的列表字段（如 rsku.migratedRskuIds）。 */
    private Object nestedList(Object parent, String key) {
        if (!(parent instanceof Map<?, ?> map)) {
            return null;
        }
        return map.get(key);
    }

    /** 回滚写字段（readField 的逆操作，仅白名单字段）。 */
    private void writeField(RspuMaster target, String field, Object value) {
        switch (field) {
            case "productName" -> target.setProductName((String) value);
            case "description" -> target.setDescription((String) value);
            case "retailPrice" -> target.setRetailPrice(value == null ? null : new java.math.BigDecimal(String.valueOf(value)));
            case "colorPrimaryName" -> target.setColorPrimaryName((String) value);
            case "materialTags" -> target.setMaterialTags((String) value);
            case "fabricTags" -> target.setFabricTags((String) value);
            case "sixDimTags" -> target.setSixDimTags((String) value);
            case "referencePriceBand" -> target.setReferencePriceBand((String) value);
            case "productLevel" -> target.setProductLevel((String) value);
            case "warrantyYears" -> target.setWarrantyYears(value == null ? null : Integer.valueOf(String.valueOf(value)));
            case "keySpecs" -> target.setKeySpecs((String) value);
            default -> throw new BusinessException("字段不允许回滚写入: " + field);
        }
    }


}
