package com.rsdp.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.rsdp.entity.ExcelImportProductGroup;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@link ExcelImportProductGroup} Mapper（设计文档 v3.3 §21）。
 *
 * <p>不可变性（§20.1 / §22）：Service 层只使用 SELECT / INSERT（{@link BaseMapper#insert}），
 * 不调用 UPDATE / DELETE；过期清理只允许走 §44 受限 GC 函数（P0-E）。</p>
 */
@Mapper
public interface ExcelImportProductGroupMapper extends BaseMapper<ExcelImportProductGroup> {

    /**
     * 按 product_group_key 升序列出某 Revision 的全部产品组
     * （与 §47.1.3 SnapshotHashMaterial 的 groups 排序口径一致，保证重放校验字节稳定）。
     *
     * @param batchId         批次 ID
     * @param previewRevision Revision 号
     * @return 该 Revision 下的全部 immutable 产品组
     */
    @Select("SELECT * FROM excel_import_product_group"
        + " WHERE batch_id = #{batchId} AND preview_revision = #{previewRevision}"
        + " ORDER BY product_group_key ASC")
    List<ExcelImportProductGroup> listByRevision(@Param("batchId") String batchId,
                                                 @Param("previewRevision") long previewRevision);
}
