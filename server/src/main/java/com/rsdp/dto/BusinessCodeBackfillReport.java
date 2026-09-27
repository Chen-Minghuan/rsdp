package com.rsdp.dto;

import lombok.Value;

import java.util.List;
import java.util.Map;

/**
 * RSPU/RSKU 业务编码历史回填报告（ADR 007 存量补码，P0-A Phase 3 前置）。
 *
 * <p>计数口径：</p>
 * <ul>
 *   <li>{@code rspuScanned}/{@code rskuScanned}：扫描到的编码为空（IS NULL）的未软删记录数；</li>
 *   <li>{@code rspuAssigned}/{@code rskuAssigned}：本次实际发号并写库的记录数
 *       （dryRun 时为「会发号」的预估值，不写库、不占流水号）；</li>
 *   <li>{@code rspuSkipped}/{@code rskuSkipped}：因数据不满足发号条件而跳过的记录数，
 *       按原因分类计数见 {@code rspuSkippedByReason}/{@code rskuSkippedByReason}；</li>
 *   <li>{@code entries}：逐条明细，便于人工核对与审计。</li>
 * </ul>
 */
@Value
public class BusinessCodeBackfillReport {

    /** 是否为 Dry Run（只统计不写库、不占流水号）。 */
    boolean dryRun;
    /** 批大小。 */
    int batchSize;
    /** 实际执行批次数（RSPU 阶段 + RSKU 阶段合计）。 */
    int batchesExecuted;

    long rspuScanned;
    int rspuAssigned;
    int rspuSkipped;
    /** RSPU 跳过原因分类计数（键见 BusinessCodeBackfillService.REASON_*）。 */
    Map<String, Integer> rspuSkippedByReason;

    long rskuScanned;
    int rskuAssigned;
    int rskuSkipped;
    /** RSKU 跳过原因分类计数（键见 BusinessCodeBackfillService.REASON_*）。 */
    Map<String, Integer> rskuSkippedByReason;

    /** 逐条处理明细。 */
    List<Entry> entries;

    /**
     * 单条处理明细。
     */
    @Value
    public static class Entry {
        /** 记录类型：RSPU。 */
        public static final String TYPE_RSPU = "RSPU";
        /** 记录类型：RSKU。 */
        public static final String TYPE_RSKU = "RSKU";

        /** 已发号并写库（dryRun：会发号）。 */
        public static final String OUTCOME_ASSIGNED = "ASSIGNED";
        /** 数据不满足发号条件，跳过（dryRun 同口径）。 */
        public static final String OUTCOME_SKIPPED = "SKIPPED";

        /** 记录类型：RSPU / RSKU。 */
        String recordType;
        /** RSPU ID 或 RSKU ID。 */
        String recordId;
        /** 处理结果：ASSIGNED / SKIPPED。 */
        String outcome;
        /** 发放的业务编码（SKIPPED 时为空）。 */
        String code;
        /** 跳过原因（ASSIGNED 时为空）。 */
        String reason;
        /** 补充说明。 */
        String note;
    }
}
