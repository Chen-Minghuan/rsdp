package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.rsdp.config.properties.ExcelImportGovernanceProperties;
import com.rsdp.entity.ExcelImportBatch;
import com.rsdp.entity.ExcelImportPreviewRevision;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.ExcelImportPreviewRevisionMapper;
import com.rsdp.mapper.ExcelImportSnapshotGcMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Preview Snapshot 受限 GC（设计文档 v3.3 §44 / §44.2 / §20.1，P0-B3）。
 *
 * <p>语义（与 §44.2 逐条对齐）：</p>
 * <ul>
 *   <li>只选 {@code retention_hold=false}、非导入中（无 running/pending import attempt 的
 *       代理条件，Attempt 表 P0-F 落地后并入判定）、快照已达保留期的 batch——
 *       未 Confirm 180 天（可配置缩短），已 Confirm / Import 365 天；</li>
 *   <li>以 batch_id 为单位清理：每批次一个独立事务（{@code REQUIRES_NEW} 语义经
 *       {@link TransactionTemplate} 逐批提交），锁 batch 行后<b>重新校验</b> retention /
 *       hold / 导入中状态，不接受「强制跳过校验」参数；任一守护条件失败整批保留；</li>
 *   <li>清理顺序：确认/导入证据写入 {@code snapshot_archive_summary}（只含 revision
 *       number / hash / version / confirm actor/time / 结果计数，不含 Raw / Payload /
 *       成本 / 可解密敏感值）→ 清空活跃指针（{@code preview_revision / confirmed_revision}）
 *       → 删除快照底表（group / issue / sensitive 经组合 FK 级联）；归档与删除在
 *       同一 Batch 锁定事务内完成；</li>
 *   <li>归档后 Confirm / Import 在「活跃指针为 NULL 且归档摘要存在」时返回
 *       {@code 410 snapshot_expired}（§45），不得根据摘要重建或重放历史 Snapshot；</li>
 *   <li>任务记录 scanned / archived / skipped / failed 计数，单批失败只记日志、
 *       下一轮幂等续跑。</li>
 * </ul>
 *
 * <p>生产库底表 DML 限权（Runtime Role REVOKE UPDATE/DELETE + NOLOGIN Owner 的
 * SECURITY DEFINER 函数）属 §85.2 B 类上线门禁运维项，dev 库复用高权限账号，
 * 不在应用迁移中内建（与 V17/V18 先例一致）；应用侧唯一入口是
 * {@link com.rsdp.mapper.ExcelImportSnapshotGcMapper}。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelImportSnapshotGcService {

    /** GC 操作审计操作人。 */
    private static final String OPERATOR = "system";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ExcelImportGovernanceProperties properties;
    private final ExcelImportSnapshotGcMapper gcMapper;
    private final ExcelImportBatchMapper batchMapper;
    private final ExcelImportPreviewRevisionMapper revisionMapper;
    private final AuditLogService auditLogService;
    private final PlatformTransactionManager transactionManager;

    /**
     * 定时执行（默认每日一次，低峰由部署侧安排初始延迟/间隔；
     * 管理员也可直接调用 {@link #runOnce()} 手动触发）。
     */
    @Scheduled(fixedDelayString = "${rsdp.excel-import.snapshot-gc.interval-ms:86400000}",
        initialDelayString = "${rsdp.excel-import.snapshot-gc.initial-delay-ms:300000}")
    public void scheduledGc() {
        if (!properties.getSnapshotGc().isEnabled()) {
            return;
        }
        try {
            runOnce();
        } catch (Exception e) {
            // 调度线程自身异常不抛出，下个周期重试（幂等续跑）
            log.error("Snapshot GC 运行异常（下一轮幂等续跑）", e);
        }
    }

    /**
     * 执行一轮 GC：扫描候选 → 逐批独立事务归档。
     *
     * @return 运行计数摘要（scanned / archived / failed）
     */
    public Map<String, Integer> runOnce() {
        ExcelImportGovernanceProperties.SnapshotGc gc = properties.getSnapshotGc();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime unconfirmedThreshold = now.minusDays(gc.getUnconfirmedRetentionDays());
        LocalDateTime confirmedThreshold = now.minusDays(gc.getConfirmedRetentionDays());

        List<ExcelImportBatch> candidates = gcMapper.selectGcCandidates(
            unconfirmedThreshold, confirmedThreshold, gc.getBatchSize());
        int archived = 0;
        int failed = 0;
        for (ExcelImportBatch candidate : candidates) {
            try {
                Boolean ok = new TransactionTemplate(transactionManager).execute(status -> archiveBatch(candidate));
                if (Boolean.TRUE.equals(ok)) {
                    archived++;
                }
            } catch (Exception e) {
                failed++;
                log.error("Snapshot GC 单批归档失败（下一轮幂等续跑）: batchId={}", candidate.getBatchId(), e);
            }
        }
        log.info("Snapshot GC 完成: scanned={}, archived={}, skipped={}, failed={}",
            candidates.size(), archived, candidates.size() - archived - failed, failed);
        return Map.of("scanned", candidates.size(), "archived", archived, "failed", failed);
    }

    /**
     * 单批归档（独立事务）：锁 batch 行 → 重新校验守护条件 → 写归档摘要并清指针 →
     * 删除快照底表。任一步失败整个事务回滚，批次完整保留。
     *
     * @return true = 已归档；false = 守护条件不再满足（并发确认/导入/置 hold），跳过
     */
    private boolean archiveBatch(ExcelImportBatch candidate) {
        String batchId = candidate.getBatchId();
        ExcelImportBatch batch = batchMapper.selectForUpdate(batchId);
        if (batch == null) {
            return false;
        }
        // GC 函数内部重新校验（§20.1 第 4 条）：hold / 导入中 / 指针仍在 / 保留期
        if (Boolean.TRUE.equals(batch.getRetentionHold())
            || "importing".equals(batch.getStatus())
            || batch.getPreviewRevision() == null
            || !retentionExpired(batch)) {
            return false;
        }

        List<ExcelImportPreviewRevision> revisions = revisionMapper.listByBatch(batchId);
        String summary = buildArchiveSummary(batch, revisions);

        int updated = gcMapper.archiveSnapshotPointers(batchId, summary);
        if (updated != 1) {
            // 并发确认/导入/置 hold/在途 Attempt：放弃本批，整批保留到下一轮
            return false;
        }
        // P0-F（§49 清理顺序）：Attempt 对 Revision 的 FK 为 ON DELETE RESTRICT，
        // 必须先删 Attempt（Item 级联）再删 Revision；守护已保证无 pending/running Attempt
        int deletedAttempts = gcMapper.deleteAttemptsByBatch(batchId);
        int deleted = gcMapper.deleteRevisionsByBatch(batchId);
        log.info("Snapshot 已归档清理: batchId={}, revisions={}, attempts={}, confirmedRevision={}",
            batchId, deleted, deletedAttempts, batch.getConfirmedRevision());
        auditLogService.logUpdate("excel_import_batch", batchId,
            Map.of("previewRevision", String.valueOf(batch.getPreviewRevision())),
            Map.of("previewRevision", "null", "snapshotArchiveSummary", "written",
                "archivedRevisions", String.valueOf(deleted)),
            OPERATOR);
        return true;
    }

    /**
     * 保留期判定（§44）：已 Confirm / Import 按 confirmed_at 起算 365 天；
     * 未 Confirm 按当前指针 Revision 的 created_at 起算 180 天（候选 SQL 已按同口径过滤，
     * 此处为锁内复检）。
     */
    private boolean retentionExpired(ExcelImportBatch batch) {
        ExcelImportGovernanceProperties.SnapshotGc gc = properties.getSnapshotGc();
        LocalDateTime now = LocalDateTime.now();
        if (batch.getConfirmedRevision() != null) {
            LocalDateTime confirmedAt = batch.getConfirmedAt();
            return confirmedAt == null
                || confirmedAt.isBefore(now.minusDays(gc.getConfirmedRetentionDays()));
        }
        ExcelImportPreviewRevision current = revisionMapper.selectByRevision(
            batch.getBatchId(), batch.getPreviewRevision());
        return current == null
            || current.getCreatedAt().isBefore(now.minusDays(gc.getUnconfirmedRetentionDays()));
    }

    /**
     * 归档摘要（§44.2）：只保留 revision number、hash/version、confirm actor/time、
     * 批次终态与成功/失败计数；禁止包含 Raw、Normalized Payload、成本或敏感值。
     */
    private String buildArchiveSummary(ExcelImportBatch batch, List<ExcelImportPreviewRevision> revisions) {
        ObjectNode summary = MAPPER.createObjectNode();
        summary.put("archivedAt", LocalDateTime.now().toString());
        summary.put("archiveReason", "retention_expired");
        summary.put("batchStatus", batch.getStatus());
        summary.put("revisionCount", revisions.size());
        ArrayNode revisionNodes = summary.putArray("revisions");
        for (ExcelImportPreviewRevision revision : revisions) {
            ObjectNode node = revisionNodes.addObject();
            node.put("previewRevision", revision.getPreviewRevision());
            node.put("snapshotHash", revision.getSnapshotHash());
            node.put("hashVersion", revision.getHashVersion());
            node.put("createdAt", revision.getCreatedAt() == null ? null : revision.getCreatedAt().toString());
        }
        if (batch.getConfirmedRevision() != null) {
            summary.put("confirmedRevision", batch.getConfirmedRevision());
            summary.put("confirmedBy", batch.getConfirmedBy());
            summary.put("confirmedAt", batch.getConfirmedAt() == null ? null : batch.getConfirmedAt().toString());
        }
        summary.put("successCount", batch.getSuccessCount() == null ? 0 : batch.getSuccessCount());
        summary.put("failedCount", batch.getFailedCount() == null ? 0 : batch.getFailedCount());
        return summary.toString();
    }
}
