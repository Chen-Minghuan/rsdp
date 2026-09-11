package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档批量导入结果（批次状态查询响应）。
 */
@Data
public class DocumentImportResult {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 批次状态：pending/processing/done/partial_success/failed。
     */
    private String status;

    /**
     * 批次级错误信息（status=failed 时）。
     */
    private String errorMessage;

    /**
     * PDF 总页数。
     */
    private int totalPages;

    /**
     * 已处理页数（进度轮询）。
     */
    private int processedPages;

    /**
     * 识别为产品页的页数。
     */
    private int productPages;

    /**
     * 检测到的产品总数。
     */
    private int totalProducts;

    /**
     * 成功创建 RSPU 的数量。
     */
    private int successCount;

    /**
     * 失败数量。
     */
    private int failedCount;

    /**
     * 图片查重命中（已存在）跳过建档的数量。
     */
    private int skippedCount;

    /**
     * 创建的异步任务 ID 列表。
     */
    private List<String> taskIds = new ArrayList<>();

    /**
     * 创建的 RSPU ID 列表。
     */
    private List<String> rspuIds = new ArrayList<>();

    /**
     * 失败明细。
     */
    private List<DocumentImportFailure> failures = new ArrayList<>();
}
