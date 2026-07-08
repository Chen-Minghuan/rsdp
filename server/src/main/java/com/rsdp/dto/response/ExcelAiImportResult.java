package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 辅助导入执行结果。
 */
@Data
public class ExcelAiImportResult {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 总行数。
     */
    private int totalRows;

    /**
     * 成功创建 RSPU 的数量。
     */
    private int successCount;

    /**
     * 失败数量。
     */
    private int failedCount;

    /**
     * 创建的 RSPU ID 列表。
     */
    private List<String> rspuIds = new ArrayList<>();

    /**
     * 创建的异步任务 ID 列表。
     */
    private List<String> taskIds = new ArrayList<>();

    /**
     * 失败明细。
     */
    private List<ExcelAiImportFailure> failures = new ArrayList<>();
}
