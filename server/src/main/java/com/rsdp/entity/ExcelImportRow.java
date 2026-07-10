package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Excel 行级导入记录实体。
 *
 * <p>追踪每一行 Excel 数据的处理状态、原始值、映射结果、生成的实体 ID 等。</p>
 */
@Data
@TableName("excel_import_row")
public class ExcelImportRow {

    @TableId(type = IdType.AUTO)
    private Long rowId;

    private String batchId;
    private Integer excelRowNumber;
    private String rowType;
    private Long parentRowId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rawData;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String mappedFields;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String selectedPriceColumns;

    private String status;
    private String processingStage;

    private String generatedRspuId;
    private String generatedVariantId;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String generatedRskuIds;

    private String failureReason;
    private String failureStage;

    private Integer extractedImageCount;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String imageAssetIds;

    private String aiTaskId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
