package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 导入 Preview Snapshot Header（设计文档 v3.3 §20.1）。
 *
 * <p>per-sheet（batch）边界的不可变 Revision 元数据：品类策略（SINGLE/MIXED）、
 * mapping_hash、normalizer_version、snapshot_hash。复合主键
 * {@code (batch_id, preview_revision)}，应用只允许 INSERT 新 Revision，
 * 禁止 UPDATE / DELETE（§20.1 / §22 / §46）。</p>
 *
 * <p>{@code excel_import_batch.preview_revision / confirmed_revision} 只是指针；
 * 真正不可变的策略与哈希保存在本表。</p>
 */
@Data
@TableName("excel_import_preview_revision")
public class ExcelImportPreviewRevision {

    /** 批次 ID（per-sheet 边界，复合主键之一） */
    private String batchId;

    /** Revision 号，batch 内单调递增（复合主键之一） */
    private Long previewRevision;

    private Integer sourceSheetIndex;
    private String sourceSheetName;

    /** 品类模式：SINGLE / MIXED / null（未选导入方式的旧路径，09 方案 §16） */
    private String categoryMode;
    private String categoryHint;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String candidateCategoryCodes;

    private String mappingHash;
    private String normalizerVersion;

    /** Canonical Hash 协议版本（§47.1.6），当前固定 1 */
    private Integer hashVersion;

    /** 对 §47.1.3 SnapshotHashMaterial 的 SHA-256 */
    private String snapshotHash;

    private String createdBy;
    private LocalDateTime createdAt;
}
