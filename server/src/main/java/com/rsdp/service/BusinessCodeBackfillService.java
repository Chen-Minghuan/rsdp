package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.BusinessCodeBackfillReport;
import com.rsdp.dto.Dimensions;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuVariant;
import com.rsdp.entity.RskuSupply;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuVariantMapper;
import com.rsdp.mapper.RskuSupplyMapper;
import com.rsdp.util.DictNormalizes;
import com.rsdp.util.SizeSpecParser;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RSPU/RSKU 业务编码历史回填服务（ADR 007 存量补码，P0-A Phase 3 前置）。
 *
 * <p>背景：ADR 007 §13.3 原决策「存量数据不补码、清库重录」，但业务库实际未清空，
 * 只读画像发现存量 {@code rspu_code} / {@code rsku_code} 全量 NULL；同时各创建入口的
 * 容错发号（尺寸码缺失、风格未归一、AI 推断失败、RSPU 未发号时 RSKU 无码创建）也会留空。
 * 本服务为存量 NULL 数据统一补号。</p>
 *
 * <p>回填口径：</p>
 * <ul>
 *   <li>扫描 {@code rspu_code IS NULL} 的未软删 RSPU（{@code @TableLogic} 自动排除软删，
 *       软删记录不补号）；逐条调用 {@link RspuCodeService#assignCode} 发号写库；</li>
 *   <li>RSPU 阶段完成后再扫描 {@code rsku_code IS NULL} 的未软删 RSKU，
 *       逐条调用 {@link RskuCodeService#assignCode}（依赖所属 RSPU 已有 rspu_code，
 *       故 RSKU 阶段必须在 RSPU 阶段之后）；</li>
 *   <li>编码不可变更原则：只处理编码为空的记录；已有非 NULL 编码的记录不在扫描范围内，
 *       底层 {@code assignCodeIfAbsent}（{@code WHERE rspu_code IS NULL}）与
 *       {@code RskuCodeService.assignCode} 的既有编码短路双重保证绝不重写。</li>
 * </ul>
 *
 * <p>RSPU 发号参数解析（ADR 007 §13.1：尺寸码必须显式传入或由数据推断，不允许默认 X）：</p>
 * <ol>
 *   <li>品类码：{@code rspu_master.category_code}，须存在于 category 字典；</li>
 *   <li>风格/职级码：{@code positioning_label} 经 style/grade 字典归一
 *       （字典码忽略大小写 → 字典名精确匹配，覆盖存量中文标签），未归一（含「待识别」）跳过；</li>
 *   <li>尺寸码：优先取变体显式 {@code size_code}（经 size 字典归一）；
 *       缺失时按变体 dimensions JSON / sizeText 解析最大边毫米数，
 *       走 {@link RspuCodeService#inferSizeCodeFromMm} 阈值推断（字典感知降级）；
 *       仍无法确定则跳过，绝不默认 X。</li>
 * </ol>
 *
 * <p>工程特性（对齐 {@link SupplierIdentityBackfillService} 既有风格）：</p>
 * <ul>
 *   <li>幂等可重跑：扫描条件即 {@code IS NULL}，已补号记录重跑不再命中；
 *       assignCode 对已有编码短路返回，不产生重复流水号消耗之外的副作用；</li>
 *   <li>dryRun：只统计会发号/会跳过并输出明细；不调用 assignCode
 *       （连流水号计数器也不占用），零写库；</li>
 *   <li>分批：按 {@code batchSize}（默认 {@value #DEFAULT_BATCH_SIZE}）成批处理，
 *       变体与所属 RSPU 明细按批 IN 装载（消除 N+1）；</li>
 *   <li>不中断：单条发号失败（BusinessException / 数据约束）仅记告警并计入跳过，继续批次。</li>
 * </ul>
 */
@Slf4j
@Service
public class BusinessCodeBackfillService {

    /** 默认批大小。 */
    public static final int DEFAULT_BATCH_SIZE = 200;
    /** 默认审计操作人标记（历史回填非用户操作，显式标记便于审计追溯）。 */
    public static final String DEFAULT_OPERATOR = "business-code-backfill";

    /** 跳过原因：品类码缺失。 */
    public static final String REASON_CATEGORY_MISSING = "CATEGORY_MISSING";
    /** 跳过原因：品类码不在 category 字典。 */
    public static final String REASON_CATEGORY_NOT_IN_DICT = "CATEGORY_NOT_IN_DICT";
    /** 跳过原因：风格/职级码无法由 positioning_label 归一。 */
    public static final String REASON_STYLE_UNRESOLVED = "STYLE_UNRESOLVED";
    /** 跳过原因：尺寸码无法确定（变体无显式 size_code 且无法按尺寸推断；ADR 007 §13.1 禁止默认 X）。 */
    public static final String REASON_SIZE_UNRESOLVED = "SIZE_UNRESOLVED";
    /** 跳过原因：所属 RSPU 不存在（含已软删）。 */
    public static final String REASON_RSPU_MISSING = "RSPU_MISSING";
    /** 跳过原因：所属 RSPU 尚无业务编码（RSPU 阶段被跳过或发号失败）。 */
    public static final String REASON_RSPU_CODE_MISSING = "RSPU_CODE_MISSING";
    /** 跳过原因：工厂代码缺失。 */
    public static final String REASON_FACTORY_MISSING = "FACTORY_MISSING";
    /** 跳过原因：材质码缺失。 */
    public static final String REASON_MATERIAL_MISSING = "MATERIAL_MISSING";
    /** 跳过原因：发号调用失败（reason 前缀，后接异常信息）。 */
    public static final String REASON_ASSIGN_FAILED = "ASSIGN_FAILED";

    private final RspuMapper rspuMapper;
    private final RspuVariantMapper rspuVariantMapper;
    private final RskuSupplyMapper rskuSupplyMapper;
    private final DictService dictService;
    private final RspuCodeService rspuCodeService;
    private final RskuCodeService rskuCodeService;
    private final ObjectMapper objectMapper;

    public BusinessCodeBackfillService(RspuMapper rspuMapper,
                                       RspuVariantMapper rspuVariantMapper,
                                       RskuSupplyMapper rskuSupplyMapper,
                                       DictService dictService,
                                       RspuCodeService rspuCodeService,
                                       RskuCodeService rskuCodeService,
                                       ObjectMapper objectMapper) {
        this.rspuMapper = rspuMapper;
        this.rspuVariantMapper = rspuVariantMapper;
        this.rskuSupplyMapper = rskuSupplyMapper;
        this.dictService = dictService;
        this.rspuCodeService = rspuCodeService;
        this.rskuCodeService = rskuCodeService;
        this.objectMapper = objectMapper;
    }

    /**
     * 回填参数。
     */
    @Value
    public static class BackfillOptions {
        /** true = 只统计不写库（连流水号计数器也不占用）。 */
        boolean dryRun;
        /** 批大小（&gt;0）。 */
        int batchSize;
        /** 审计操作人（透传给 assignCode 的审计日志）。 */
        String operator;

        /** 真实执行（默认批大小、默认操作人标记）。 */
        public static BackfillOptions defaults() {
            return new BackfillOptions(false, DEFAULT_BATCH_SIZE, DEFAULT_OPERATOR);
        }

        /** Dry Run（默认批大小、默认操作人标记）。 */
        public static BackfillOptions dryRun() {
            return new BackfillOptions(true, DEFAULT_BATCH_SIZE, DEFAULT_OPERATOR);
        }
    }

    /**
     * 执行业务编码回填（或 Dry Run）。
     *
     * <p>先 RSPU 阶段、后 RSKU 阶段（RSKU 编码依赖所属 RSPU 的 rspu_code，
     * 同一轮内 RSPU 补号成功后其 RSKU 即可在本轮 RSKU 阶段补号）。</p>
     *
     * @param options 回填参数
     * @return 回填报告（分类计数 + 逐条明细）
     */
    public BusinessCodeBackfillReport backfill(BackfillOptions options) {
        int batchSize = options.getBatchSize() > 0 ? options.getBatchSize() : DEFAULT_BATCH_SIZE;
        String operator = StringUtils.hasText(options.getOperator())
            ? options.getOperator() : DEFAULT_OPERATOR;
        Accumulator acc = new Accumulator(options.isDryRun(), batchSize);

        backfillRspuCodes(operator, batchSize, acc);
        backfillRskuCodes(operator, batchSize, acc);

        log.info("业务编码回填{}完成: rspuScanned={}, rspuAssigned={}, rspuSkipped={}, "
                + "rskuScanned={}, rskuAssigned={}, rskuSkipped={}",
            options.isDryRun() ? "(dryRun) " : "",
            acc.rspuScanned, acc.rspuAssigned, acc.rspuSkippedTotal(),
            acc.rskuScanned, acc.rskuAssigned, acc.rskuSkippedTotal());
        return acc.build();
    }

    // ---- RSPU 阶段 ----

    private void backfillRspuCodes(String operator, int batchSize, Accumulator acc) {
        // 扫描 rspu_code 为空的未软删 RSPU（@TableLogic 自动排除软删）
        List<RspuMaster> candidates = rspuMapper.selectList(new QueryWrapper<RspuMaster>()
            .isNull("rspu_code")
            .orderByAsc("rspu_id"));
        acc.rspuScanned = candidates.size();
        if (candidates.isEmpty()) {
            return;
        }

        // 字典快照：一次加载，全阶段复用（归一 + 校验）
        List<CategoryDict> categoryDict = dictService.listByType("category");
        List<CategoryDict> styleDict = dictService.listByType("style");
        List<CategoryDict> gradeDict = dictService.listByType("grade");
        List<CategoryDict> sizeDict = dictService.listByType("size");

        for (List<RspuMaster> batch : partition(candidates, batchSize)) {
            acc.batches++;
            Map<String, List<RspuVariant>> variantsByRspu = loadVariants(batch);
            for (RspuMaster rspu : batch) {
                processRspu(rspu, variantsByRspu.getOrDefault(rspu.getRspuId(), List.of()),
                    categoryDict, styleDict, gradeDict, sizeDict, operator, acc);
            }
        }
    }

    /** 批量装载本批 RSPU 的未软删变体（消除逐 RSPU 查询的 N+1）。 */
    private Map<String, List<RspuVariant>> loadVariants(List<RspuMaster> batch) {
        Map<String, List<RspuVariant>> byRspu = new LinkedHashMap<>();
        List<String> rspuIds = batch.stream().map(RspuMaster::getRspuId).toList();
        List<RspuVariant> variants = rspuVariantMapper.selectList(new QueryWrapper<RspuVariant>()
            .select("variant_id", "rspu_id", "size_code", "size_text", "dimensions")
            .in("rspu_id", rspuIds));
        for (RspuVariant variant : variants) {
            byRspu.computeIfAbsent(variant.getRspuId(), k -> new ArrayList<>()).add(variant);
        }
        return byRspu;
    }

    /** 单条 RSPU 处理：解析发号参数 → 发号（dryRun 只分类）。 */
    private void processRspu(RspuMaster rspu, List<RspuVariant> variants,
                             List<CategoryDict> categoryDict, List<CategoryDict> styleDict,
                             List<CategoryDict> gradeDict, List<CategoryDict> sizeDict,
                             String operator, Accumulator acc) {
        String categoryCode = StringUtils.hasText(rspu.getCategoryCode())
            ? rspu.getCategoryCode().trim().toUpperCase() : null;
        if (categoryCode == null) {
            skipRspu(acc, rspu, REASON_CATEGORY_MISSING, "品类码为空");
            return;
        }
        if (!DictNormalizes.isValid(categoryCode, categoryDict)) {
            skipRspu(acc, rspu, REASON_CATEGORY_NOT_IN_DICT, "品类码不在 category 字典: " + categoryCode);
            return;
        }
        String styleCode = resolveStyleCode(rspu.getPositioningLabel(), styleDict, gradeDict);
        if (styleCode == null) {
            skipRspu(acc, rspu, REASON_STYLE_UNRESOLVED,
                "风格/职级码未归一: " + rspu.getPositioningLabel());
            return;
        }
        String sizeCode = resolveSizeCode(variants, sizeDict);
        if (sizeCode == null) {
            skipRspu(acc, rspu, REASON_SIZE_UNRESOLVED,
                "尺寸码无法确定（变体无 size_code 且无法按尺寸推断；禁止默认 X）");
            return;
        }

        if (acc.dryRun) {
            acc.rspuAssigned++;
            acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSPU, rspu.getRspuId(),
                BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED, null, null,
                String.format("dryRun: 会发号（%s-%s-***-%s）", categoryCode, styleCode, sizeCode));
            return;
        }
        try {
            String code = rspuCodeService.assignCode(
                rspu.getRspuId(), categoryCode, styleCode, sizeCode, operator);
            acc.rspuAssigned++;
            acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSPU, rspu.getRspuId(),
                BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED, code, null, "发号并写库");
        } catch (RuntimeException e) {
            // 单条失败不阻断批次：记告警并计入跳过
            log.warn("RSPU 业务编码回填失败，跳过该条，rspuId={}，原因={}", rspu.getRspuId(), e.getMessage());
            skipRspu(acc, rspu, REASON_ASSIGN_FAILED, e.getMessage());
        }
    }

    /** 风格/职级码归一：先 style 字典后 grade 字典（字典码忽略大小写 → 字典名精确匹配）。 */
    private String resolveStyleCode(String positioningLabel,
                                    List<CategoryDict> styleDict, List<CategoryDict> gradeDict) {
        String code = DictNormalizes.normalizeOrNull(positioningLabel, styleDict);
        if (code != null) {
            return code;
        }
        return DictNormalizes.normalizeOrNull(positioningLabel, gradeDict);
    }

    /**
     * 尺寸码解析：变体显式 size_code（字典归一）优先；
     * 缺失时按变体 dimensions JSON / sizeText 最大边毫米数走
     * {@link RspuCodeService#inferSizeCodeFromMm} 阈值推断；仍无法确定返回 null。
     *
     * <p>尺寸解析胶水逻辑与 {@code AiRecognitionPersistenceService#inferSizeCodeFromVariants}
     * 口径一致（纯解析逻辑副本，避免改动 AI 识别链路及其既有测试；后续可收敛为公共方法）。</p>
     */
    private String resolveSizeCode(List<RspuVariant> variants, List<CategoryDict> sizeDict) {
        for (RspuVariant variant : variants) {
            String explicit = DictNormalizes.normalizeOrNull(variant.getSizeCode(), sizeDict);
            if (explicit != null) {
                return explicit;
            }
        }
        long maxMm = 0;
        for (RspuVariant variant : variants) {
            maxMm = Math.max(maxMm, maxVariantDimensionMm(variant));
        }
        return maxMm > 0 ? rspuCodeService.inferSizeCodeFromMm(maxMm) : null;
    }

    /** 单个变体的最大边毫米数（dimensions JSON 优先，sizeText 解析兜底；解析失败按 0）。 */
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

    /** 尺寸对象的最大边（统一换算毫米）。 */
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

    // ---- RSKU 阶段 ----

    private void backfillRskuCodes(String operator, int batchSize, Accumulator acc) {
        // 扫描 rsku_code 为空的未软删 RSKU（@TableLogic 自动排除软删）
        List<RskuSupply> candidates = rskuSupplyMapper.selectList(new QueryWrapper<RskuSupply>()
            .isNull("rsku_code")
            .orderByAsc("rsku_id"));
        acc.rskuScanned = candidates.size();
        if (candidates.isEmpty()) {
            return;
        }

        for (List<RskuSupply> batch : partition(candidates, batchSize)) {
            acc.batches++;
            Map<String, RspuMaster> rspuById = loadOwners(batch);
            for (RskuSupply rsku : batch) {
                processRsku(rsku, rspuById.get(rsku.getRspuId()), operator, acc);
            }
        }
    }

    /** 批量装载本批 RSKU 的所属 RSPU（未软删；已软删的 RSPU 视为缺失，其子 RSKU 跳过不补号）。 */
    private Map<String, RspuMaster> loadOwners(List<RskuSupply> batch) {
        Map<String, RspuMaster> byId = new LinkedHashMap<>();
        List<String> rspuIds = batch.stream().map(RskuSupply::getRspuId).distinct().toList();
        for (RspuMaster rspu : rspuMapper.selectBatchIds(rspuIds)) {
            byId.put(rspu.getRspuId(), rspu);
        }
        return byId;
    }

    /** 单条 RSKU 处理：前置校验 → 发号（dryRun 只分类）。 */
    private void processRsku(RskuSupply rsku, RspuMaster owner, String operator, Accumulator acc) {
        if (!StringUtils.hasText(rsku.getFactoryCode())) {
            skipRsku(acc, rsku, REASON_FACTORY_MISSING, "工厂代码为空");
            return;
        }
        if (!StringUtils.hasText(rsku.getMaterialCode())) {
            skipRsku(acc, rsku, REASON_MATERIAL_MISSING, "材质码为空");
            return;
        }
        if (owner == null) {
            skipRsku(acc, rsku, REASON_RSPU_MISSING, "所属 RSPU 不存在或已软删: " + rsku.getRspuId());
            return;
        }
        if (!StringUtils.hasText(owner.getRspuCode())) {
            skipRsku(acc, rsku, REASON_RSPU_CODE_MISSING,
                "所属 RSPU 尚无业务编码: " + rsku.getRspuId());
            return;
        }

        if (acc.dryRun) {
            acc.rskuAssigned++;
            acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSKU, rsku.getRskuId(),
                BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED, null, null,
                String.format("dryRun: 会发号（%s-%s-%s-***）",
                    owner.getRspuCode(), rsku.getFactoryCode().trim().toUpperCase(),
                    rsku.getMaterialCode().trim().toUpperCase()));
            return;
        }
        try {
            String code = rskuCodeService.assignCode(rsku.getRskuId(), rsku.getRspuId(),
                rsku.getFactoryCode(), rsku.getMaterialCode(), operator);
            acc.rskuAssigned++;
            acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSKU, rsku.getRskuId(),
                BusinessCodeBackfillReport.Entry.OUTCOME_ASSIGNED, code, null, "发号并写库");
        } catch (RuntimeException e) {
            // 单条失败不阻断批次：记告警并计入跳过
            log.warn("RSKU 业务编码回填失败，跳过该条，rskuId={}，原因={}", rsku.getRskuId(), e.getMessage());
            skipRsku(acc, rsku, REASON_ASSIGN_FAILED, e.getMessage());
        }
    }

    // ---- 明细与累加 ----

    private void skipRspu(Accumulator acc, RspuMaster rspu, String reason, String note) {
        acc.rspuSkippedByReason.merge(reason, 1, Integer::sum);
        acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSPU, rspu.getRspuId(),
            BusinessCodeBackfillReport.Entry.OUTCOME_SKIPPED, null, reason, note);
    }

    private void skipRsku(Accumulator acc, RskuSupply rsku, String reason, String note) {
        acc.rskuSkippedByReason.merge(reason, 1, Integer::sum);
        acc.addEntry(BusinessCodeBackfillReport.Entry.TYPE_RSKU, rsku.getRskuId(),
            BusinessCodeBackfillReport.Entry.OUTCOME_SKIPPED, null, reason, note);
    }

    private static <T> List<List<T>> partition(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

    /** 报告累加器。 */
    private static final class Accumulator {
        private final boolean dryRun;
        private final int batchSize;
        private int batches;
        private long rspuScanned;
        private int rspuAssigned;
        private final Map<String, Integer> rspuSkippedByReason = new LinkedHashMap<>();
        private long rskuScanned;
        private int rskuAssigned;
        private final Map<String, Integer> rskuSkippedByReason = new LinkedHashMap<>();
        private final List<BusinessCodeBackfillReport.Entry> entries = new ArrayList<>();

        private Accumulator(boolean dryRun, int batchSize) {
            this.dryRun = dryRun;
            this.batchSize = batchSize;
        }

        private int rspuSkippedTotal() {
            return rspuSkippedByReason.values().stream().mapToInt(Integer::intValue).sum();
        }

        private int rskuSkippedTotal() {
            return rskuSkippedByReason.values().stream().mapToInt(Integer::intValue).sum();
        }

        private void addEntry(String recordType, String recordId, String outcome,
                              String code, String reason, String note) {
            entries.add(new BusinessCodeBackfillReport.Entry(
                recordType, recordId, outcome, code, reason, note));
        }

        private BusinessCodeBackfillReport build() {
            return new BusinessCodeBackfillReport(
                dryRun, batchSize, batches,
                rspuScanned, rspuAssigned, rspuSkippedTotal(), Map.copyOf(rspuSkippedByReason),
                rskuScanned, rskuAssigned, rskuSkippedTotal(), Map.copyOf(rskuSkippedByReason),
                List.copyOf(entries));
        }
    }
}
