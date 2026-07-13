package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ExcelImportRowMapper extends BaseMapper<ExcelImportRow> {

    @Select("SELECT * FROM excel_import_row WHERE batch_id = #{batchId} ORDER BY excel_row_number")
    List<ExcelImportRow> selectByBatchId(@Param("batchId") String batchId);
}
