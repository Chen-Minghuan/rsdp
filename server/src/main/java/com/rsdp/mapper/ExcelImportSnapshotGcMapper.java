package com.rsdp.mapper;

import com.rsdp.entity.ExcelImportBatch;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Preview Snapshot 受限 GC Mapper（设计文档 v3.3 §44.2 / §20.1）。
 *
 * <p>本 Mapper 是 Snapshot 底表「指针归档 + 过期删除」的唯一入口，只允许由
 * {@code ExcelImportSnapshotGcService} 调用；普通 Batch / Revision Repository
 * 不暴露这些写方法（§20.1：不可变性由 Repository API 与数据库权限共同保证）。</p>
 *
 * <p>生产库的「Migration Owner 与 Runtime App Role 分离 + 底表 REVOKE UPDATE/DELETE +
 * 独立 NOLOGIN Owner 持有的 SECURITY DEFINER GC 函数」属 §85.2 B 类上线门禁运维项，
 * dev 库复用高权限账号，不在应用迁移中内建（与 V17/V18 先例一致）。本 Mapper 的
 * 每条语句都自带 retention / hold / 导入中状态前置条件，等价于 GC 函数内部
 * 重新校验语义，且不提供「强制跳过校验」参数。</p>
 */
@Mapper
public interface ExcelImportSnapshotGcMapper {

    /**
     * GC 候选批次：未 hold、非导入中、快照指针仍在、且已达保留期。
     *
     * <p>保留期口径（§44）：未 Confirm 的快照按当前指针 Revision 的 created_at 起算
     * {@code unconfirmedThreshold}；已 Confirm / Import 的快照按 confirmed_at 起算
     * {@code confirmedThreshold}（不早于 Attempt 保留期——P0-F 已并入 Attempt 判定：
     * 批次内仍有 pending/running 的 Import Attempt 时快照整批保留（§48-§50，
     * 执行中/待执行的 Attempt 依赖快照底表做 Replay-first 恢复，GC 不得抽空其输入）；
     * {@code status <> 'importing'} 仍保留为批次级代理条件双保险）。</p>
     *
     * @param unconfirmedThreshold 未确认快照的 created_at 上限（早于此时间到期）
     * @param confirmedThreshold   已确认快照的 confirmed_at 上限
     * @param limit                单次扫描候选上限（分页提交，避免长事务）
     * @return 候选批次（含指针与确认元数据）
     */
    @Select("SELECT b.* FROM excel_import_batch b"
        + " JOIN excel_import_preview_revision r"
        + "   ON r.batch_id = b.batch_id AND r.preview_revision = b.preview_revision"
        + " WHERE b.retention_hold = false"
        + "   AND b.status <> 'importing'"
        + "   AND b.preview_revision IS NOT NULL"
        + "   AND ("
        + "        (b.confirmed_revision IS NULL AND r.created_at < #{unconfirmedThreshold})"
        + "     OR (b.confirmed_revision IS NOT NULL AND b.confirmed_at < #{confirmedThreshold})"
        + "   )"
        // §44/§48（P0-F B3 收口）：批次内仍有 pending/running Import Attempt 时整批保留——
        // Attempt 的 Replay-first 恢复依赖快照底表，GC 不得抽空在途 Attempt 的输入
        + "   AND NOT EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "                   WHERE a.batch_id = b.batch_id AND a.status IN ('pending', 'running'))"
        + " ORDER BY b.batch_id"
        + " LIMIT #{limit}")
    List<ExcelImportBatch> selectGcCandidates(@Param("unconfirmedThreshold") LocalDateTime unconfirmedThreshold,
                                              @Param("confirmedThreshold") LocalDateTime confirmedThreshold,
                                              @Param("limit") int limit);

    /**
     * 指针归档：写非敏感归档摘要并清空活跃指针（§44.2 清理顺序的关键步骤，
     * 与快照删除在同一 Batch 锁定事务内完成）。
     *
     * <p>守护条件（不满足则 0 行，调用方放弃本批次）：未 hold、非导入中、指针仍在——
     * 并发确认/导入/编辑/置 hold 时归档自动放弃，整批评审留到下一轮。</p>
     *
     * @param batchId 批次 ID
     * @param summary 归档摘要 JSON（只含 revision number / hash / version / confirm 元数据 /
     *                结果计数，禁止 Raw / Normalized Payload / 成本 / 可解密敏感值）
     * @return 影响行数；1 成功，0 表示守护条件已不满足
     */
    @Update("UPDATE excel_import_batch SET"
        + " preview_revision = NULL, confirmed_revision = NULL,"
        + " confirmed_by = NULL, confirmed_at = NULL,"
        + " mapping_hash = NULL, normalizer_version = NULL,"
        + " snapshot_archive_summary = CAST(#{summary} AS jsonb), updated_at = now()"
        + " WHERE batch_id = #{batchId}"
        + "   AND retention_hold = false"
        + "   AND status <> 'importing'"
        + "   AND preview_revision IS NOT NULL"
        // P0-F B3 收口：归档指针前复核无 pending/running Import Attempt（候选判定
        // 与归档之间的竞态窗口由本守护兜底，并发 confirm 提交的新 Attempt 会拦下归档）
        + "   AND NOT EXISTS (SELECT 1 FROM excel_import_attempt a"
        + "                   WHERE a.batch_id = #{batchId} AND a.status IN ('pending', 'running'))")
    int archiveSnapshotPointers(@Param("batchId") String batchId, @Param("summary") String summary);

    /**
     * 删除批次下全部 Import Attempt（excel_import_attempt_item 经 FK ON DELETE CASCADE
     * 一并清理；Attempt 对 Revision 的 FK 为 ON DELETE RESTRICT，必须先删 Attempt
     * 再删 Revision，§49 清理顺序）。
     *
     * <p>只能在 {@link #archiveSnapshotPointers} 成功后同事务、{@link #deleteRevisionsByBatch}
     * 之前调用；候选/归档双重守护已保证无 pending/running Attempt，此处删除的均为
     * done/failed 终态 Attempt 的留存记录（随快照一并到期）。</p>
     *
     * @param batchId 批次 ID
     * @return 删除的 Attempt 行数
     */
    @Delete("DELETE FROM excel_import_attempt WHERE batch_id = #{batchId}")
    int deleteAttemptsByBatch(@Param("batchId") String batchId);

    /**
     * 删除批次下全部 Preview Revision（excel_import_product_group /
     * excel_import_sensitive_value 经组合 FK ON DELETE CASCADE 一并清理）。
     *
     * <p>只能在 {@link #archiveSnapshotPointers} 成功后同事务调用——先归档指针再删底表，
     * 保证「活跃指针为 NULL 且归档摘要存在 → 410 snapshot_expired」的判定不受删除竞态影响。</p>
     *
     * @param batchId 批次 ID
     * @return 删除的 Revision 行数
     */
    @Delete("DELETE FROM excel_import_preview_revision WHERE batch_id = #{batchId}")
    int deleteRevisionsByBatch(@Param("batchId") String batchId);
}
