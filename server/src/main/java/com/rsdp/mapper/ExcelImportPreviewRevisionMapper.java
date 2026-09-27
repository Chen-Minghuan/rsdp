package com.rsdp.mapper;

import com.rsdp.entity.ExcelImportPreviewRevision;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * {@link ExcelImportPreviewRevision} Mapper（设计文档 v3.3 §20.1）。
 *
 * <p>不可变性（§20.1 / §22 / §46）：本 Mapper 有意不继承 BaseMapper，
 * 只暴露 SELECT / INSERT——应用层不存在 UPDATE / DELETE 单个 Revision 的入口；
 * 过期清理只允许走 §44 受限 GC 函数（P0-E），不经由本 Mapper。</p>
 */
@Mapper
public interface ExcelImportPreviewRevisionMapper {

    /**
     * 插入新 Revision（唯一写入口）。并发同号撞复合主键时由上层翻译为
     * 409 preview_revision_conflict（§47）。
     */
    @Insert("INSERT INTO excel_import_preview_revision ("
        + " batch_id, preview_revision, source_sheet_index, source_sheet_name,"
        + " category_mode, category_hint, candidate_category_codes,"
        + " mapping_hash, normalizer_version, hash_version, snapshot_hash, created_by)"
        + " VALUES ("
        + " #{batchId}, #{previewRevision}, #{sourceSheetIndex}, #{sourceSheetName},"
        + " #{categoryMode}, #{categoryHint}, CAST(#{candidateCategoryCodes} AS jsonb),"
        + " #{mappingHash}, #{normalizerVersion}, #{hashVersion}, #{snapshotHash}, #{createdBy})")
    int insert(ExcelImportPreviewRevision revision);

    /** 按复合主键精确读取某一 Revision。 */
    @Select("SELECT * FROM excel_import_preview_revision"
        + " WHERE batch_id = #{batchId} AND preview_revision = #{previewRevision}")
    ExcelImportPreviewRevision selectByRevision(@Param("batchId") String batchId,
                                                @Param("previewRevision") long previewRevision);

    /** 读取 batch 下最新 Revision（无快照时返回 null）。 */
    @Select("SELECT * FROM excel_import_preview_revision"
        + " WHERE batch_id = #{batchId} ORDER BY preview_revision DESC LIMIT 1")
    ExcelImportPreviewRevision selectLatest(@Param("batchId") String batchId);

    /** 按 Revision 号升序列出 batch 的全部历史 Revision。 */
    @Select("SELECT * FROM excel_import_preview_revision"
        + " WHERE batch_id = #{batchId} ORDER BY preview_revision ASC")
    List<ExcelImportPreviewRevision> listByBatch(@Param("batchId") String batchId);
}
