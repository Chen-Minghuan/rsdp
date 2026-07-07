package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * PDF 文档批量导入结果。
 */
@Data
public class DocumentImportResult {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * PDF 总页数。
     */
    private int totalPages;

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
