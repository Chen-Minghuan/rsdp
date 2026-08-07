package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExcelImportRowMapper extends BaseMapper<ExcelImportRow> {

    @Select("SELECT * FROM excel_import_row WHERE batch_id = #{batchId} ORDER BY excel_row_number LIMIT 1000")
    List<ExcelImportRow> selectByBatchId(@Param("batchId") String batchId);

    /**
     * 按批次 + Excel 行号查询唯一行记录。
     *
     * @param batchId        批次 ID
     * @param excelRowNumber Excel 物理行号（1-based）
     * @return 行记录；不存在时为 null
     */
    @Select("SELECT * FROM excel_import_row WHERE batch_id = #{batchId} AND excel_row_number = #{excelRowNumber} LIMIT 1")
    ExcelImportRow selectByBatchIdAndRowNumber(@Param("batchId") String batchId,
                                               @Param("excelRowNumber") int excelRowNumber);

    /**
     * 删除批次下全部行级记录（done 批次重新导入前清理上一轮结果）。
     *
     * @param batchId 批次 ID
     * @return 删除行数
     */
    @Delete("DELETE FROM excel_import_row WHERE batch_id = #{batchId}")
    int deleteByBatchId(@Param("batchId") String batchId);
}
