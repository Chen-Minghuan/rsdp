package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel AI 辅助导入批次记录。
 */
@Data
@TableName("excel_import_batch")
public class ExcelImportBatch {

    @TableId
    private String batchId;

    private String fileName;
    private String storagePath;
    private String status;
    private Integer totalRows;
    private Integer successCount;
    private Integer failedCount;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String columnMapping;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String previewRows;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String priceColumns;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String failures;

    private String factoryCode;
    private String factoryName;
    private String shippingWarehouseId;
    private String shippingFrom;
    private Integer defaultLeadTimeDays;
    private Integer defaultMoq;
    private String categoryHint;
    private Integer headerRowCount;
    private Integer dataStartRow;
    private String importNote;
    /** 多 Sheet 文件导入的工作表索引（0-based，默认 0） */
    private Integer sheetIndex;
    private LocalDateTime processedAt;

    // ---- P0-B1 Immutable Preview Snapshot / Revision（v3.3 §45，Flyway V14）----

    /** 当前最新 Preview Revision 指针；不可变策略/哈希在 excel_import_preview_revision */
    private Long previewRevision;
    /** 当前 mapping 哈希（§47.1.4） */
    private String mappingHash;
    /** 当前 Normalizer 版本 */
    private String normalizerVersion;
    /** 已确认 Revision 指针（Confirm 语义 P0-B3 收口） */
    private Long confirmedRevision;
    private String confirmedBy;
    private LocalDateTime confirmedAt;
    /** 导入 Policy 的 UI 草稿（§45/§47.1.5） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String importPolicy;
    /** 审计/争议保留标记（§44）：true 时 GC 必须跳过整个 batch */
    private Boolean retentionHold;
    /** 过期归档非敏感摘要（§44/§45），仅受限 GC 函数写入 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String snapshotArchiveSummary;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
