package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.DocumentImportBatch;
import org.apache.ibatis.annotations.Mapper;

/**
 * 文档（PDF）导入批次 Mapper。
 */
@Mapper
public interface DocumentImportBatchMapper extends BaseMapper<DocumentImportBatch> {
}
