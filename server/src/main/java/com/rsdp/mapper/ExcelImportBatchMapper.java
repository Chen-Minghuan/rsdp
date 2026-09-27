package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

@Mapper
public interface ExcelImportBatchMapper extends BaseMapper<ExcelImportBatch> {

    /**
     * 原子抢占批次导入权：仅当批次仍处于 pending / done / failed 时置为 importing。
     *
     * <p>用于防止并发重复导入（替代先查状态再判断的 check-then-act 竞态）。
     * done 批次允许重新抢占，支撑「以更新模式重新导入」；failed 批次（3.2 异步化后
     * 异步执行的致命失败终态）允许重新抢占重试；抢占时同步重置上一轮
     * 导入结果字段（success/failed/failures/processed_at），importing 中拒绝。</p>
     *
     * @param batchId 批次 ID
     * @return 影响行数；0 表示批次正在导入中
     */
    @Update("UPDATE excel_import_batch SET status = 'importing', success_count = 0, failed_count = 0,"
        + " failures = '[]'::jsonb, processed_at = NULL, updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status IN ('pending', 'done', 'failed')")
    int claimForImport(@Param("batchId") String batchId);

    /**
     * 导入心跳：逐行刷新 importing 批次的 updated_at，防止长导入被
     * {@link #reapStaleImporting} 误判为僵死批次误收割（3.2 异步化后导入在后台
     * 线程执行，耗时可能超过收割阈值）。
     *
     * @param batchId 批次 ID
     * @return 影响行数；0 表示批次已不在 importing 状态（无需心跳）
     */
    @Update("UPDATE excel_import_batch SET updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status = 'importing'")
    int touchImporting(@Param("batchId") String batchId);

    /**
     * 复位批次状态：仅当批次仍处于 importing 时退回 pending。
     *
     * <p>导入主流程异常时调用，避免批次永久卡死 importing 导致用户无法重试。</p>
     *
     * @param batchId 批次 ID
     * @return 影响行数；0 表示批次已不在 importing 状态（无需复位）
     */
    @Update("UPDATE excel_import_batch SET status = 'pending', updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status = 'importing'")
    int resetToPending(@Param("batchId") String batchId);

    /**
     * 恢复 done 批次上一轮导入结果（导入主流程失败复位时调用）：
     * 状态回到 done 并还原计数、失败明细与完成时间，done 批次重导失败不丢历史结果。
     *
     * @param batchId      批次 ID
     * @param successCount 原成功数
     * @param failedCount  原失败数
     * @param failures     原失败明细 JSON
     * @param processedAt  原完成时间
     * @return 影响行数；0 表示批次已不在 importing 状态（无需恢复）
     */
    @Update("UPDATE excel_import_batch SET status = 'done', success_count = #{successCount},"
        + " failed_count = #{failedCount}, failures = CAST(#{failures} AS jsonb),"
        + " processed_at = #{processedAt}, updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status = 'importing'")
    int restoreBatchResult(@Param("batchId") String batchId, @Param("successCount") Integer successCount,
                           @Param("failedCount") Integer failedCount, @Param("failures") String failures,
                           @Param("processedAt") LocalDateTime processedAt);

    /**
     * 收割超时 importing 批次：状态停留在 importing 且长时间未更新，视为导入线程已消亡
     * （如 JVM 崩溃/重启），复位为 pending 允许用户重试。
     *
     * <p>updated_at 为 NULL 的历史批次无从判断进入时间，一并收割。</p>
     *
     * @param threshold 超时阈值（updated_at 早于此时间视为超时）
     * @return 复位行数
     */
    @Update("UPDATE excel_import_batch SET status = 'pending', updated_at = now()"
        + " WHERE status = 'importing' AND (updated_at IS NULL OR updated_at < #{threshold})")
    int reapStaleImporting(@Param("threshold") LocalDateTime threshold);

    /**
     * 行锁读取批次（P0-B1，v3.3 §47 Optimistic Lock 的串行化点）。
     *
     * <p>Preview Revision 创建在事务内先锁住 batch 行，再校验
     * {@code preview_revision} 与编辑命令携带的 {@code expectedRevision}
     * 是否一致，保证同一 batch（per-sheet 边界）的并发编辑串行 adjudication。</p>
     *
     * @param batchId 批次 ID
     * @return 批次实体；不存在返回 null
     */
    @Select("SELECT * FROM excel_import_batch WHERE batch_id = #{batchId} FOR UPDATE")
    ExcelImportBatch selectForUpdate(@Param("batchId") String batchId);

    /**
     * 原子推进 Preview Revision 指针（P0-B1，v3.3 §45 / §46 / §47）。
     *
     * <p>CAS 语义：仅当当前 {@code preview_revision} 与 {@code expectedRevision}
     * 一致（{@code IS NOT DISTINCT FROM}，双方均可为 NULL 表示尚无快照）时才推进，
     * 同时刷新当前 mapping_hash / normalizer_version。0 行表示期间已被其他编辑
     * 推进，调用方必须抛 409 preview_revision_conflict 并回滚新 Revision 写入。</p>
     *
     * <p>P0-B3 收口（§27 Resolution 与 Revision 规则）：任何 Revision 推进都会改变
     * Import 输入，因此同事务清空活跃 Confirm 指针（confirmed_revision / confirmed_by /
     * confirmed_at）——已确认快照被编辑后必须重新 Confirm，不允许旧 Confirm 放行新输入。</p>
     *
     * @param batchId           批次 ID
     * @param expectedRevision  期望的当前 Revision（NULL = 期望尚无快照）
     * @param newRevision       新 Revision 号
     * @param mappingHash       当前 mapping 哈希
     * @param normalizerVersion 当前 Normalizer 版本
     * @return 影响行数；1 成功，0 冲突
     */
    @Update("UPDATE excel_import_batch SET preview_revision = #{newRevision},"
        + " mapping_hash = #{mappingHash}, normalizer_version = #{normalizerVersion},"
        + " confirmed_revision = NULL, confirmed_by = NULL, confirmed_at = NULL, updated_at = now()"
        + " WHERE batch_id = #{batchId}"
        + " AND preview_revision IS NOT DISTINCT FROM #{expectedRevision,jdbcType=BIGINT}")
    int advancePreviewRevision(@Param("batchId") String batchId,
                               @Param("expectedRevision") Long expectedRevision,
                               @Param("newRevision") long newRevision,
                               @Param("mappingHash") String mappingHash,
                               @Param("normalizerVersion") String normalizerVersion);

    /**
     * 写入 Confirm 指针（P0-B3，v3.3 §45 / §72：confirm 成功处写 confirmed_revision）。
     *
     * <p>CAS 语义：仅当当前 {@code preview_revision} 仍等于被确认的 Revision 时才写入
     * （期间若有编辑推进了 Revision，确认对象已失效，0 行 → 调用方抛
     * 409 preview_revision_conflict）。可重复确认同一 Revision（幂等刷新操作人/时间）。</p>
     *
     * @param batchId  批次 ID
     * @param revision 被确认的 Revision 号
     * @param operator 确认操作人（sys_user.user_id）
     * @return 影响行数；1 成功，0 表示 Revision 已被推进（冲突）
     */
    @Update("UPDATE excel_import_batch SET confirmed_revision = #{revision},"
        + " confirmed_by = #{operator}, confirmed_at = now(), updated_at = now()"
        + " WHERE batch_id = #{batchId}"
        + " AND preview_revision = #{revision}")
    int confirmRevision(@Param("batchId") String batchId,
                        @Param("revision") long revision,
                        @Param("operator") String operator);
}
