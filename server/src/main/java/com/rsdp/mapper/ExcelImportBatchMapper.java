package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportBatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ExcelImportBatchMapper extends BaseMapper<ExcelImportBatch> {

    /**
     * 原子抢占批次导入权：仅当批次仍处于 pending 时置为 importing。
     *
     * <p>用于防止并发重复导入（替代先查状态再判断的 check-then-act 竞态）。</p>
     *
     * @param batchId 批次 ID
     * @return 影响行数；0 表示批次正在导入或已完成
     */
    @Update("UPDATE excel_import_batch SET status = 'importing', updated_at = now()"
        + " WHERE batch_id = #{batchId} AND status = 'pending'")
    int claimForImport(@Param("batchId") String batchId);
}
