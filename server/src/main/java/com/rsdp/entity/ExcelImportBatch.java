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

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
