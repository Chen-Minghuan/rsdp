package com.rsdp.dto;

import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * external_code → supplier_product_ref 历史回填报告（设计文档 v3.3 §9 Phase 2）。
 *
 * <p>计数口径：</p>
 * <ul>
 *   <li>{@code scanned}：扫描到的 external_code 非空的未软删 RSPU 数；</li>
 *   <li>{@code processed}：本次实际处理（含自动回填与入队）的 RSPU 数；</li>
 *   <li>{@code created}/{@code reused}：自动回填新建 / 复用既有 Supplier Ref 数
 *       （dryRun 时为「会新建 / 会复用」的预估值，不写库）；</li>
 *   <li>{@code queuedByType}：按治理项类型分类的入队数（dryRun 时为「会入队」预估）；</li>
 *   <li>{@code entries}：逐条明细，便于人工核对与审计。</li>
 * </ul>
 */
@Value
public class SupplierIdentityBackfillReport {

    /** 待人工治理类型：无法确定工厂。 */
    public static final String TYPE_UNRESOLVED_FACTORY = "UNRESOLVED_FACTORY";
    /** 待人工治理类型：同一编码对应多个候选工厂。 */
    public static final String TYPE_MULTIPLE_FACTORY_CANDIDATES = "MULTIPLE_FACTORY_CANDIDATES";
    /** 待人工治理类型：多厂历史关系不清。 */
    public static final String TYPE_UNCLEAR_MULTI_FACTORY_HISTORY = "UNCLEAR_MULTI_FACTORY_HISTORY";
    /** 待人工治理类型：旧数据冲突（含 SOURCE_CODE_REUSED）。 */
    public static final String TYPE_CONFLICT = "CONFLICT";

    /** 是否为 Dry Run（只统计不写库）。 */
    boolean dryRun;
    /** 批大小（每批装载供应关系明细的 RSPU 数上限）。 */
    int batchSize;
    /** 实际执行批次数。 */
    int batchesExecuted;

    long scanned;
    int processed;
    int created;
    int reused;
    /** 待人工治理总数（queuedByType 合计）。 */
    int queued;
    /** 按治理类型分类的计数（键见本类 TYPE_* 常量）。 */
    Map<String, Integer> queuedByType;
    /** 逐条处理明细。 */
    List<Entry> entries;

    /**
     * 单条处理明细。
     */
    @Value
    public static class Entry {
        /** 自动回填：新建 Supplier Ref（dryRun：会新建）。 */
        public static final String OUTCOME_CREATED = "CREATED";
        /** 自动回填：复用既有 Supplier Ref 并刷新 provenance（dryRun：会复用）。 */
        public static final String OUTCOME_REUSED = "REUSED";
        /** 未自动处理，列入待人工治理清单（报告计数 + 明细，不持久化）。 */
        public static final String OUTCOME_QUEUED = "QUEUED";

        String rspuId;
        /** 历史外部编码原文（rspu_master.external_code）。 */
        String externalCode;
        /** 归一后的编码。 */
        String normalizedCode;
        /** 工厂编码（可定位时）。 */
        String factoryCode;
        /** 处理结果：CREATED / REUSED / QUEUED。 */
        String outcome;
        /** 治理类型（QUEUED 时非空，取值见外层 TYPE_* 常量）。 */
        String itemType;
        /** 补充说明（冲突详情、候选工厂等）。 */
        String note;
    }
}
