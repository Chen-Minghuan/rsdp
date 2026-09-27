package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.SupplierIdentityBackfillReport;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.SupplierProductRef;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.SourceCodeReusedException;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.util.SourceCodeNormalizer;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * external_code → supplier_product_ref 历史回填服务（设计文档 v3.3 §9 Phase 2 Backfill）。
 *
 * <p>回填口径（§9 Phase 2）：</p>
 * <ul>
 *   <li>扫描存量未软删 {@code rspu_master}（{@code external_code} 非空），
 *       以 {@code external_code} 为 {@code source_product_code} 原文、
 *       {@code source_namespace = 'default'}、
 *       {@code code_normalizer_version = }{@link SourceCodeNormalizer#CURRENT_VERSION}，
 *       经 {@link SupplierProductRefService#resolveOrCreate} 建立/复用 Supplier Ref；</li>
 *   <li>工厂来源：RSPU 下未软删 {@code rsku_supply} 行的 distinct {@code factory_code}；</li>
 *   <li>provenance：{@code first/last_seen_source_system} 回填为可配置的来源标记
 *       （默认 {@value #DEFAULT_SOURCE_SYSTEM}）；历史导入批次不可考，
 *       {@code first/last_import_batch_id} 留空（§9「如可得」）；provenance 不参与身份唯一性。</li>
 * </ul>
 *
 * <p>四类不自动处理数据（捕获后列入报告的待人工治理清单，不中断批次）：</p>
 * <ol>
 *   <li>无法确定工厂 → {@code UNRESOLVED_FACTORY}；</li>
 *   <li>同一编码对应多个候选工厂 → {@code MULTIPLE_FACTORY_CANDIDATES}；</li>
 *   <li>多厂历史关系不清 → {@code UNCLEAR_MULTI_FACTORY_HISTORY}
 *       （同一归一编码跨多个 RSPU 且组内存在工厂归属歧义）；</li>
 *   <li>旧数据冲突 → {@code CONFLICT}（同键已绑定其他 RSPU 的 {@code SOURCE_CODE_REUSED}，
 *       或编码归一后为空等不可用数据）。</li>
 * </ol>
 *
 * <p>分组判定矩阵（同一归一编码一组）：</p>
 * <ul>
 *   <li>单 RSPU：0 工厂 → UNRESOLVED_FACTORY；&gt;1 工厂 → MULTIPLE_FACTORY_CANDIDATES；
 *       恰好 1 工厂 → 自动回填；</li>
 *   <li>多 RSPU：任一成员工厂数 ≠ 1 → 全组 UNCLEAR_MULTI_FACTORY_HISTORY；
 *       全部成员恰好 1 工厂且互不相同 → 各自自动回填（§77.1：Factory A/S001 与 Factory B/S001
 *       是不同身份键，允许并存）；≥2 个成员共享同一工厂 → 按 rspu_id 序首个自动回填，
 *       其余撞 {@code SOURCE_CODE_REUSED} 入 CONFLICT。</li>
 * </ul>
 *
 * <p>工程特性：</p>
 * <ul>
 *   <li>幂等可重跑：{@code resolveOrCreate} 同键同 RSPU 复用不产生重复 ref；
 *       待治理清单为报告制（不持久化），重跑按当轮重新统计；</li>
 *   <li>dryRun：只统计会建多少 ref、多少进队列并输出分类清单，不写任何库；</li>
 *   <li>分批：候选先按归一编码全量分组（分组正确性不依赖批边界），
 *       再按 {@code batchSize} 成批装载供应关系明细并处理（§74.1 分批风格）。</li>
 * </ul>
 */
@Slf4j
@Service
public class SupplierIdentityBackfillService {

    /** 历史回填 provenance 默认来源标记（仅 provenance，不参与 Identity）。 */
    public static final String DEFAULT_SOURCE_SYSTEM = "legacy-backfill";
    /** 默认批大小。 */
    public static final int DEFAULT_BATCH_SIZE = 200;

    private final RspuMapper rspuMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final SupplierProductRefService supplierProductRefService;
    private final ObjectMapper objectMapper;

    public SupplierIdentityBackfillService(RspuMapper rspuMapper,
                                           RskuSupplyMapper rskuSupplyMapper,
                                           SupplierProductRefService supplierProductRefService,
                                           ObjectMapper objectMapper) {
        this.rspuMapper = rspuMapper;
        this.rskuSupplyMapper = rskuSupplyMapper;
        this.supplierProductRefService = supplierProductRefService;
        this.objectMapper = objectMapper;
    }

    /**
     * 回填参数。
     */
    @Value
    public static class BackfillOptions {
        /** true = 只统计不写库。 */
        boolean dryRun;
        /** 每批装载供应关系明细的 RSPU 数上限（&gt;0）。 */
        int batchSize;
        /** provenance 来源标记（first/last_seen_source_system）。 */
        String sourceSystem;

        /** 真实执行（默认批大小、默认来源标记）。 */
        public static BackfillOptions defaults() {
            return new BackfillOptions(false, DEFAULT_BATCH_SIZE, DEFAULT_SOURCE_SYSTEM);
        }

        /** Dry Run（默认批大小、默认来源标记）。 */
        public static BackfillOptions dryRun() {
            return new BackfillOptions(true, DEFAULT_BATCH_SIZE, DEFAULT_SOURCE_SYSTEM);
        }
    }

    /**
     * 执行回填（或 Dry Run）。
     *
     * @param options 回填参数
     * @return 回填报告（分类计数 + 逐条明细）
     */
    public SupplierIdentityBackfillReport backfill(BackfillOptions options) {
        int batchSize = options.getBatchSize() > 0 ? options.getBatchSize() : DEFAULT_BATCH_SIZE;
        String sourceSystem = StringUtils.hasText(options.getSourceSystem())
            ? options.getSourceSystem() : DEFAULT_SOURCE_SYSTEM;

        // 第一步：扫描候选（仅轻量列），按归一编码全量分组——分组正确性不依赖批边界
        List<RspuMaster> candidates = scanCandidates();
        Map<String, List<RspuMaster>> groupsByCode = groupByNormalizedCode(candidates);

        Accumulator acc = new Accumulator(options.isDryRun(), batchSize);

        // 第二步：按批大小把组切成批，逐批装载供应关系明细并处理
        List<Map.Entry<String, List<RspuMaster>>> pending = new ArrayList<>();
        int pendingSize = 0;
        for (Map.Entry<String, List<RspuMaster>> group : groupsByCode.entrySet()) {
            pending.add(group);
            pendingSize += group.getValue().size();
            if (pendingSize >= batchSize) {
                processBatch(pending, sourceSystem, acc);
                pending = new ArrayList<>();
                pendingSize = 0;
            }
        }
        if (!pending.isEmpty()) {
            processBatch(pending, sourceSystem, acc);
        }

        log.info("external_code 回填{}完成: scanned={}, processed={}, created={}, reused={}, queued={}",
            options.isDryRun() ? "(dryRun) " : "",
            candidates.size(), acc.processed, acc.created, acc.reused, acc.queuedTotal());
        return acc.build(candidates.size());
    }

    /** 扫描 external_code 非空的未软删 RSPU（@TableLogic 自动排除软删）。 */
    private List<RspuMaster> scanCandidates() {
        return rspuMapper.selectList(new QueryWrapper<RspuMaster>()
            .select("rspu_id", "external_code", "product_name")
            .isNotNull("external_code")
            .ne("external_code", "")
            .orderByAsc("rspu_id"));
    }

    /** 按归一编码分组；key 为归一编码（TreeMap 保证处理顺序确定）。 */
    private Map<String, List<RspuMaster>> groupByNormalizedCode(List<RspuMaster> candidates) {
        Map<String, List<RspuMaster>> groups = new TreeMap<>();
        for (RspuMaster rspu : candidates) {
            String normalized = SourceCodeNormalizer.normalize(rspu.getExternalCode());
            groups.computeIfAbsent(normalized == null ? "" : normalized, k -> new ArrayList<>()).add(rspu);
        }
        return groups;
    }

    /** 处理一批：装载本批 RSPU 的供应关系，逐组判定。 */
    private void processBatch(List<Map.Entry<String, List<RspuMaster>>> batch,
                              String sourceSystem, Accumulator acc) {
        acc.batches++;
        List<String> rspuIds = new ArrayList<>();
        batch.forEach(g -> g.getValue().forEach(r -> rspuIds.add(r.getRspuId())));
        Map<String, List<RskuSupply>> suppliesByRspu = loadSupplies(rspuIds);

        for (Map.Entry<String, List<RspuMaster>> group : batch) {
            processGroup(group.getKey(), group.getValue(), suppliesByRspu, sourceSystem, acc);
        }
    }

    /** 批量装载供应关系（消除逐 RSPU 查询的 N+1；@TableLogic 自动排除软删 RSKU）。 */
    private Map<String, List<RskuSupply>> loadSupplies(List<String> rspuIds) {
        Map<String, List<RskuSupply>> byRspu = new LinkedHashMap<>();
        if (rspuIds.isEmpty()) {
            return byRspu;
        }
        List<RskuSupply> supplies = rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>()
            .select("rsku_id", "rspu_id", "factory_code")
            .in("rspu_id", rspuIds));
        for (RskuSupply supply : supplies) {
            byRspu.computeIfAbsent(supply.getRspuId(), k -> new ArrayList<>()).add(supply);
        }
        return byRspu;
    }

    /** 逐组判定（分组判定矩阵见类注释）。 */
    private void processGroup(String normalizedCode, List<RspuMaster> members,
                              Map<String, List<RskuSupply>> suppliesByRspu,
                              String sourceSystem, Accumulator acc) {
        if (!StringUtils.hasText(normalizedCode)) {
            // 编码归一后为空：旧数据不可用 → CONFLICT
            for (RspuMaster member : members) {
                queue(member, SupplierIdentityBackfillReport.TYPE_CONFLICT, null,
                    detail("reason", "INVALID_SOURCE_CODE",
                        "message", "external_code 归一后为空，无法建立 Supplier Ref 身份"),
                    "编码归一后为空", acc);
            }
            return;
        }

        Map<String, List<RskuSupply>> memberSupplies = new LinkedHashMap<>();
        for (RspuMaster member : members) {
            memberSupplies.put(member.getRspuId(),
                suppliesByRspu.getOrDefault(member.getRspuId(), List.of()));
        }

        if (members.size() > 1 && hasAmbiguousFactoryAttribution(memberSupplies)) {
            // 多 RSPU 共享编码且存在工厂归属歧义 → 全组 UNCLEAR_MULTI_FACTORY_HISTORY
            List<String> siblingIds = members.stream().map(RspuMaster::getRspuId).sorted().toList();
            for (RspuMaster member : members) {
                queue(member, SupplierIdentityBackfillReport.TYPE_UNCLEAR_MULTI_FACTORY_HISTORY,
                    null,
                    detail("reason", "MULTI_RSPU_SHARED_CODE_WITH_AMBIGUOUS_FACTORY",
                        "siblingRspuIds", siblingIds),
                    "同码兄弟 RSPU: " + siblingIds, acc);
            }
            return;
        }

        // 单 RSPU，或多 RSPU 但每成员恰好 1 工厂：逐成员处理（共享工厂的成员按 rspu_id 序首个优先）
        List<RspuMaster> ordered = members.stream()
            .sorted(java.util.Comparator.comparing(RspuMaster::getRspuId)).toList();
        for (RspuMaster member : ordered) {
            processMember(member, memberSupplies.get(member.getRspuId()), sourceSystem, acc);
        }
    }

    /** 多 RSPU 组的歧义判定：任一成员工厂数 ≠ 1 即归属不清。 */
    private boolean hasAmbiguousFactoryAttribution(Map<String, List<RskuSupply>> memberSupplies) {
        for (List<RskuSupply> supplies : memberSupplies.values()) {
            if (distinctFactories(supplies).size() != 1) {
                return true;
            }
        }
        return false;
    }

    /** 单成员处理：0 / 多 / 恰好 1 工厂三分支。 */
    private void processMember(RspuMaster member, List<RskuSupply> supplies,
                               String sourceSystem, Accumulator acc) {
        List<String> factories = distinctFactories(supplies);
        if (factories.isEmpty()) {
            queue(member, SupplierIdentityBackfillReport.TYPE_UNRESOLVED_FACTORY, null,
                detail("reason", "NO_ALIVE_SUPPLY_ROW",
                    "message", "无法确定工厂：该 RSPU 没有未软删的 rsku_supply 行"),
                "无可用供应关系", acc);
            return;
        }
        if (factories.size() > 1) {
            queue(member, SupplierIdentityBackfillReport.TYPE_MULTIPLE_FACTORY_CANDIDATES, null,
                detail("reason", "MULTIPLE_FACTORY_CANDIDATES",
                    "factoryCodes", factories),
                "候选工厂: " + factories, acc);
            return;
        }
        resolve(member, factories.get(0), firstRskuId(supplies, factories.get(0)), sourceSystem, acc);
    }

    /** 自动回填路径：同键查重 → resolveOrCreate；冲突入 CONFLICT 队列不中断。 */
    private void resolve(RspuMaster member, String factoryCode, String rskuId,
                         String sourceSystem, Accumulator acc) {
        String rawCode = member.getExternalCode();
        String normalized = SourceCodeNormalizer.normalize(rawCode);

        Optional<SupplierProductRef> existing = supplierProductRefService.findByIdentity(
            factoryCode, SupplierProductRefService.DEFAULT_NAMESPACE, rawCode);

        if (existing.isPresent() && !existing.get().getRspuId().equals(member.getRspuId())) {
            // 同键已绑定其他 RSPU（§6.2）：不自动改绑 → CONFLICT
            queueConflict(member, factoryCode, rskuId, existing.get(), acc);
            return;
        }

        if (acc.dryRun) {
            // dryRun：只分类统计，不写库
            if (existing.isPresent()) {
                acc.reused++;
                acc.addEntry(member, normalized, factoryCode,
                    SupplierIdentityBackfillReport.Entry.OUTCOME_REUSED, null, "dryRun: 会复用既有 Ref");
            } else {
                acc.created++;
                acc.addEntry(member, normalized, factoryCode,
                    SupplierIdentityBackfillReport.Entry.OUTCOME_CREATED, null, "dryRun: 会新建 Ref");
            }
            acc.processed++;
            return;
        }

        try {
            supplierProductRefService.resolveOrCreate(new SupplierProductRefService.ResolveCommand(
                member.getRspuId(),
                factoryCode,
                SupplierProductRefService.DEFAULT_NAMESPACE,
                sourceSystem,
                rawCode,
                member.getProductName(),
                null));
            if (existing.isPresent()) {
                acc.reused++;
                acc.addEntry(member, normalized, factoryCode,
                    SupplierIdentityBackfillReport.Entry.OUTCOME_REUSED, null, "复用既有 Ref 并刷新 provenance");
            } else {
                acc.created++;
                acc.addEntry(member, normalized, factoryCode,
                    SupplierIdentityBackfillReport.Entry.OUTCOME_CREATED, null, "新建 Ref");
            }
        } catch (SourceCodeReusedException e) {
            // 与预检之间的并发兜底：同键被他方绑定 → CONFLICT，不中断批次
            queueConflict(member, factoryCode, rskuId, e.getExistingRef(), acc);
            return;
        } catch (BusinessException e) {
            // 数据不可用（如编码归一后为空）→ CONFLICT，不中断批次
            log.warn("回填成员解析失败，列入待人工治理: rspu={}, code={}, reason={}",
                member.getRspuId(), rawCode, e.getMessage());
            queue(member, SupplierIdentityBackfillReport.TYPE_CONFLICT, factoryCode,
                detail("reason", "RESOLVE_REJECTED", "message", e.getMessage()),
                "解析被拒绝: " + e.getMessage(), acc);
            return;
        }
        acc.processed++;
    }

    /** SOURCE_CODE_REUSED 冲突入队。 */
    private void queueConflict(RspuMaster member, String factoryCode, String rskuId,
                               SupplierProductRef boundRef, Accumulator acc) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("issueCode", SourceCodeReusedException.ISSUE_CODE);
        detail.put("severity", SourceCodeReusedException.SEVERITY);
        detail.put("scope", SourceCodeReusedException.SCOPE);
        detail.put("existingRefId", boundRef.getRefId());
        detail.put("boundRspuId", boundRef.getRspuId());
        detail.put("incomingRspuId", member.getRspuId());
        queue(member, SupplierIdentityBackfillReport.TYPE_CONFLICT, factoryCode,
            writeJson(detail),
            SourceCodeReusedException.ISSUE_CODE + ": 已绑定 " + boundRef.getRspuId(), acc);
    }

    /**
     * 列入待人工治理清单：报告计数 + 逐条明细 + WARN 日志，不持久化
     * （治理队列表已于 2026-09-24 评审后取消——Backfill 属一次性迁移工具，
     * 重复执行按报告重新统计即可）。
     */
    private void queue(RspuMaster member, String itemType, String factoryCode,
                       String detailJson, String note, Accumulator acc) {
        String normalized = SourceCodeNormalizer.normalize(member.getExternalCode());
        acc.queuedByType.merge(itemType, 1, Integer::sum);
        String fullNote = detailJson != null ? note + "；" + detailJson : note;
        acc.addEntry(member, normalized, factoryCode,
            SupplierIdentityBackfillReport.Entry.OUTCOME_QUEUED, itemType, fullNote);
        acc.processed++;
        log.warn("供应商身份待人工治理: type={}, rspuId={}, factory={}, note={}",
            itemType, member.getRspuId(), factoryCode, fullNote);
    }

    private List<String> distinctFactories(List<RskuSupply> supplies) {
        Set<String> factories = new LinkedHashSet<>();
        for (RskuSupply supply : supplies) {
            if (StringUtils.hasText(supply.getFactoryCode())) {
                factories.add(supply.getFactoryCode().trim());
            }
        }
        return List.copyOf(factories);
    }

    private String firstRskuId(List<RskuSupply> supplies, String factoryCode) {
        for (RskuSupply supply : supplies) {
            if (factoryCode.equals(supply.getFactoryCode() == null ? null : supply.getFactoryCode().trim())) {
                return supply.getRskuId();
            }
        }
        return null;
    }

    /** 候选工厂清单 JSON：[{"factory_code":..,"rsku_id":..},...]。无可定位供应关系时返回 null。 */
    private String candidatesJson(List<RskuSupply> supplies) {
        List<Map<String, String>> candidates = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (RskuSupply supply : supplies) {
            if (!StringUtils.hasText(supply.getFactoryCode())) {
                continue;
            }
            String factory = supply.getFactoryCode().trim();
            if (seen.add(factory)) {
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("factory_code", factory);
                entry.put("rsku_id", supply.getRskuId());
                candidates.add(entry);
            }
        }
        return candidates.isEmpty() ? null : writeJson(candidates);
    }

    private String detail(Object... keyValues) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            detail.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return writeJson(detail);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("回填治理明细 JSON 序列化失败", e);
        }
    }

    /** 报告累加器。 */
    private static final class Accumulator {
        private final boolean dryRun;
        private final int batchSize;
        private int batches;
        private int processed;
        private int created;
        private int reused;
        private final Map<String, Integer> queuedByType = new LinkedHashMap<>();
        private final List<SupplierIdentityBackfillReport.Entry> entries = new ArrayList<>();

        private Accumulator(boolean dryRun, int batchSize) {
            this.dryRun = dryRun;
            this.batchSize = batchSize;
        }

        private int queuedTotal() {
            return queuedByType.values().stream().mapToInt(Integer::intValue).sum();
        }

        private void addEntry(RspuMaster member, String normalized, String factoryCode,
                              String outcome, String itemType, String note) {
            entries.add(new SupplierIdentityBackfillReport.Entry(
                member.getRspuId(), member.getExternalCode(), normalized,
                factoryCode, outcome, itemType, note));
        }

        private SupplierIdentityBackfillReport build(long scanned) {
            return new SupplierIdentityBackfillReport(
                dryRun, batchSize, batches, scanned, processed, created, reused,
                queuedTotal(), Map.copyOf(queuedByType), List.copyOf(entries));
        }
    }
}
