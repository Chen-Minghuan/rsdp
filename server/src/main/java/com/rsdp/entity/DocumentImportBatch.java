package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档（PDF）导入批次记录（阶段 3.1：PDF 导入异步批次化）。
 *
 * <p>提交即返回 batchId，批处理复用 async_task 体系（task_type=document_import）异步执行；
 * 页级/产品级明细收敛在 JSONB 字段（pageResults/failures），不新建行表。</p>
 */
@Data
@TableName("document_import_batch")
public class DocumentImportBatch {

    @TableId
    private String batchId;

    private String fileName;
    private String storagePath;
    /** pending/processing/done/partial_success/failed */
    private String status;
    private Integer totalPages;
    /** 已处理页数（前端进度轮询） */
    private Integer processedPages;
    private Integer productPages;
    private Integer detectedProducts;
    private Integer successCount;
    private Integer failCount;
    /** 图片 contentHash 查重命中跳过建档数 */
    private Integer skipCount;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String failures;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String pageResults;

    /** 建档 product_entry 任务 ID（与 rspuIds 一一对应，供前端继续轮询各产品识别状态） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String taskIds;

    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rspuIds;

    private String categoryHint;
    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
