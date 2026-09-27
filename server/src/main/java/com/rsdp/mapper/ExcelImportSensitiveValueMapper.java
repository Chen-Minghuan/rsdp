package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportSensitiveValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@link ExcelImportSensitiveValue} Mapper（设计文档 v3.3 §24.2）。
 *
 * <p>只暴露 SELECT / INSERT：{@code value_hmac + hmac_key_version} 是创建 Revision 时的
 * 固定 Hash Material（§42.5），保留期内禁止就地重算或覆盖，故不提供 UPDATE；
 * 删除只允许走 §44 受限 GC（随整个未 hold batch 的快照级联清理）。</p>
 */
@Mapper
public interface ExcelImportSensitiveValueMapper extends BaseMapper<ExcelImportSensitiveValue> {

    /**
     * 列出某 Revision 下某 Product Group 持有的敏感值（group-owned），
     * 用于 Revision 演进时向新 Snapshot 重建 PayloadHashMaterial 敏感值条目。
     *
     * @param batchId         批次 ID
     * @param previewRevision Revision 号
     * @return 该 Revision 全部 group-owned 敏感值
     */
    @Select("SELECT * FROM excel_import_sensitive_value"
        + " WHERE batch_id = #{batchId} AND preview_revision = #{previewRevision}"
        + " AND group_id IS NOT NULL"
        + " ORDER BY group_id, value_path")
    List<ExcelImportSensitiveValue> listGroupOwnedByRevision(@Param("batchId") String batchId,
                                                             @Param("previewRevision") long previewRevision);
}
