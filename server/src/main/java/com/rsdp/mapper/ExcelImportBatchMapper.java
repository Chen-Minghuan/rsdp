package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExcelImportBatchMapper extends BaseMapper<ExcelImportBatch> {

    /**
     * 原子抢占批次导入权：仅当批次仍处于 pending 或 done 时置为 importing。
     *
     * <p>用于防止并发重复导入（替代先查状态再判断的 check-then-act 竞态）。
     * done 批次允许重新抢占，支撑「以更新模式重新导入」；抢占时同步重置上一轮
     * 导入结果字段（success/failed/failures/processed_at），importing 中拒绝。</p>
     *
     * @param batchId 批次 ID
     * @return 影响行数；0 表示批次正在导入中
     */
    @Update("UPDATE excel_import_batch SET status = 'importing', success_count = 0, failed_count = 0,"
        + " failures = '[]'::jsonb, processed_at = NULL, updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status IN ('pending', 'done')")
    int claimForImport(@Param("batchId") String batchId);

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
}
