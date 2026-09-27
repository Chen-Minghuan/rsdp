package com.rsdp.supplychain.rsku;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Quote 到目标 RSKU 的聚合校验（设计文档 v3.3 §38.1，P0-D）。
 *
 * <p>{@code quoteKey} 区分报价事实，{@code targetRskuKey} 区分当前报价的落库目标。
 * P0 每条 RSKU 只保存一份 current quote，Persist 前必须先对同一 ProductGroup 内的
 * Quote 按 {@code targetRskuKey} 聚合，不得边遍历边覆盖：</p>
 * <ul>
 *   <li>比较材料（{@link QuoteComparisonMaterial}）完全相同：合并为一个 Quote，
 *       只写入一次，所有源坐标保留为多份 Lineage；即使多个来源具有相同
 *       {@code quoteKey}，也不得将重复本身作为阻断原因
 *       （{@code DUPLICATE_QUOTE_TARGET} 只能是 {@code info / non_blocking}，
 *       即使 allowWarnings=false 也不阻断导入）；</li>
 *   <li>比较材料不同：对该 {@code targetRskuKey} 下的每个不同 {@code quoteKey} 生成
 *       {@code MULTIPLE_CURRENT_QUOTES_FOR_RSKU} / {@code blocker / quote_blocking}；
 *       即使 {@code quoteKey} 相同但价格等未参与 Key 的业务值不同，也必须阻断，
 *       并保留所有冲突来源及其值（敏感值以 HMAC Ref 表达），不得先按 Key 去重丢弃差异；</li>
 *   <li>P0 不自动选最新日期、不做币种换算、不在单位之间猜测转换。</li>
 * </ul>
 *
 * <p>聚合结果与输入遍历顺序无关：处理前先按
 * {@code (targetRskuKey, quoteKey, sourceRef)} 全序排序。</p>
 *
 * <p>本组件为纯函数，不读库；与数据库已有 current quote 的 quotePolicy 比较
 * （§47.1.5）由 Persist 层在聚合收敛为单份后执行。</p>
 */
public final class QuoteTargetAggregator {

    /** Issue：同目标多份非等价 current quote（阻断）。 */
    public static final String ISSUE_MULTIPLE_CURRENT_QUOTES = "MULTIPLE_CURRENT_QUOTES_FOR_RSKU";
    /** Issue：完全等价重复 Quote 已合并（仅提示，不阻断）。 */
    public static final String ISSUE_DUPLICATE_QUOTE_TARGET = "DUPLICATE_QUOTE_TARGET";

    private QuoteTargetAggregator() {
    }

    /**
     * 标准化 Quote 的业务比较材料（§38.1 列举的最小集合）。
     * 敏感价格只携带 HMAC Ref，不携带明文。
     *
     * @param priceValueHmac 标准化价格 value_hmac
     * @param currencyCode   币种
     * @param taxIncluded    是否含税（null 与 false 是不同材料）
     * @param priceUnit      计价单位
     * @param quoteDate      报价日期（ISO-8601，可空）
     * @param effectiveFrom  生效日期（可空）
     * @param effectiveTo    失效日期（可空）
     * @param moq            最小起订量
     * @param moqUnit        起订量单位
     */
    public record QuoteComparisonMaterial(
        String priceValueHmac,
        String currencyCode,
        Boolean taxIncluded,
        String priceUnit,
        String quoteDate,
        String effectiveFrom,
        String effectiveTo,
        Integer moq,
        String moqUnit
    ) {
    }

    /**
     * 一条待聚合 Quote。
     *
     * @param quoteKey                 报价事实键（§24.1）
     * @param targetRskuKey            落库目标键（§24.1）
     * @param variantKey               所属 variantKey
     * @param factoryCode              工厂码
     * @param supplierConfigurationKey 供应配置 Key
     * @param material                 业务比较材料
     * @param sourceRef                源坐标引用（Lineage 标识，如 groupId:rowIndex:columnIndex）
     */
    public record QuoteInput(
        String quoteKey,
        String targetRskuKey,
        String variantKey,
        String factoryCode,
        String supplierConfigurationKey,
        QuoteComparisonMaterial material,
        String sourceRef
    ) {
        public QuoteInput {
            Objects.requireNonNull(quoteKey, "quoteKey");
            Objects.requireNonNull(targetRskuKey, "targetRskuKey");
            Objects.requireNonNull(material, "material");
            Objects.requireNonNull(sourceRef, "sourceRef");
        }
    }

    /**
     * 聚合后可落库的 Quote（同一 targetRskuKey 唯一一份）。
     *
     * @param representative 代表 Quote（源坐标集合中字典序最小者）
     * @param sourceRefs     全部源坐标（多份 Lineage）
     */
    public record AggregatedQuote(QuoteInput representative, List<String> sourceRefs) {
    }

    /**
     * 聚合产出的 Issue。
     *
     * @param code       Issue 代码
     * @param severity   blocker / info
     * @param scope      quote_blocking / non_blocking
     * @param targetKey  Issue 目标键（quoteKey）
     * @param message    人类可读说明
     * @param partitions 冲突分组明细（每个分组：quoteKey + 比较材料 + 全部来源）；
     *                   保留所有冲突来源及其值，不预先去重
     */
    public record QuoteIssue(
        String code,
        String severity,
        String scope,
        String targetKey,
        String message,
        List<ConflictPartition> partitions
    ) {
        public boolean isBlocker() {
            return "blocker".equals(severity);
        }
    }

    /**
     * 冲突分组明细。
     *
     * @param quoteKey   该分组的 quoteKey
     * @param material   该分组的比较材料
     * @param sourceRefs 该分组的全部源坐标
     */
    public record ConflictPartition(String quoteKey, QuoteComparisonMaterial material,
                                    List<String> sourceRefs) {
    }

    /**
     * 聚合结果。
     *
     * @param persistableQuotes 可落库 Quote（每个 targetRskuKey 至多一份；按 targetRskuKey 升序）
     * @param issues            产出的 Issue（blocker 在前，各自按 targetKey 升序）
     */
    public record AggregationResult(List<AggregatedQuote> persistableQuotes, List<QuoteIssue> issues) {

        /**
         * 是否存在阻断 Issue。
         *
         * @return 有 blocker 返回 true
         */
        public boolean hasBlockers() {
            return issues.stream().anyMatch(QuoteIssue::isBlocker);
        }
    }

    /**
     * 对同一 ProductGroup 内的 Quote 按 {@code targetRskuKey} 聚合。
     *
     * @param quotes 标准化 Quote 列表（顺序无关）
     * @return 聚合结果（确定性：与输入顺序无关）
     */
    public static AggregationResult aggregate(List<QuoteInput> quotes) {
        // 确定性排序：结果不受遍历顺序影响（§38.1 / §77.4 顺序调换用例）
        List<QuoteInput> sorted = new ArrayList<>(quotes);
        sorted.sort(Comparator.comparing(QuoteInput::targetRskuKey)
            .thenComparing(QuoteInput::quoteKey)
            .thenComparing(QuoteInput::sourceRef));

        Map<String, List<QuoteInput>> byTarget = new TreeMap<>();
        for (QuoteInput q : sorted) {
            byTarget.computeIfAbsent(q.targetRskuKey(), k -> new ArrayList<>()).add(q);
        }

        List<AggregatedQuote> persistable = new ArrayList<>();
        List<QuoteIssue> blockers = new ArrayList<>();
        List<QuoteIssue> infos = new ArrayList<>();

        for (Map.Entry<String, List<QuoteInput>> entry : byTarget.entrySet()) {
            List<QuoteInput> group = entry.getValue();
            // 按比较材料等价关系分组（保持组内 quoteKey/sourceRef 升序——输入已全序排序）
            Map<QuoteComparisonMaterial, List<QuoteInput>> partitions = new LinkedHashMap<>();
            for (QuoteInput q : group) {
                partitions.computeIfAbsent(q.material(), k -> new ArrayList<>()).add(q);
            }

            if (partitions.size() == 1) {
                // 完全等价：合并为一个 Quote，保留全部源坐标；重复本身不阻断
                List<QuoteInput> only = partitions.values().iterator().next();
                persistable.add(new AggregatedQuote(only.get(0),
                    only.stream().map(QuoteInput::sourceRef).toList()));
                if (only.size() > 1) {
                    infos.add(new QuoteIssue(
                        ISSUE_DUPLICATE_QUOTE_TARGET, "info", "non_blocking",
                        only.get(0).quoteKey(),
                        "同一 targetRskuKey / quoteKey 的完全等价重复 Quote 已合并为一次写入，保留 "
                            + only.size() + " 份 Lineage",
                        List.of()));
                }
                continue;
            }

            // 比较材料不同：对该 targetRskuKey 下每个不同 quoteKey 生成 blocker，
            // 并保留所有冲突分组（含相同 quoteKey 但业务值不同的分组——不按 Key 去重）
            List<ConflictPartition> partitionDetails = partitions.entrySet().stream()
                .map(e -> {
                    List<QuoteInput> members = e.getValue();
                    return new ConflictPartition(
                        members.get(0).quoteKey(), e.getKey(),
                        members.stream().map(QuoteInput::sourceRef).toList());
                })
                .toList();
            List<String> distinctQuoteKeys = partitions.values().stream()
                .map(m -> m.get(0).quoteKey())
                .distinct()
                .sorted()
                .toList();
            for (String quoteKey : distinctQuoteKeys) {
                blockers.add(new QuoteIssue(
                    ISSUE_MULTIPLE_CURRENT_QUOTES, "blocker", "quote_blocking",
                    quoteKey,
                    "同一 targetRskuKey 存在 " + partitions.size()
                        + " 份比较材料不同的 Quote（涉及 " + distinctQuoteKeys.size()
                        + " 个 quoteKey），P0 每条 RSKU 只保存一份 current quote，"
                        + "请通过 Preview replace/ignore 生成新 Revision 收敛",
                    partitionDetails));
            }
        }

        List<QuoteIssue> issues = new ArrayList<>(blockers);
        issues.addAll(infos);
        return new AggregationResult(List.copyOf(persistable), List.copyOf(issues));
    }
}
