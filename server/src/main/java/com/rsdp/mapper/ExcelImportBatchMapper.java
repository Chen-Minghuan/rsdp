package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}
