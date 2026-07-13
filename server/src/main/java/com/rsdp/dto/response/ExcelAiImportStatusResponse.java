package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 辅助导入批次状态查询响应。
 */
@Data
public class ExcelAiImportStatusResponse {

    private String batchId;
    private String fileName;
    private String status;
    private int totalRows;
    private int successCount;
    private int failedCount;
    private List<ExcelAiImportFailure> failures = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
