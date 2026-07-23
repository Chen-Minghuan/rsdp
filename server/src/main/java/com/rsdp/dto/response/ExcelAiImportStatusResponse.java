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
    /**
     * 跳过的行数（从行级记录聚合：说明行/重复表头行/已存在且未开启更新）。
     */
    private int skippedCount;
    private List<ExcelAiImportFailure> failures = new ArrayList<>();
    /**
     * 异步识别任务与 RSPU 的成对关联（从行级记录聚合），
     * 前端超时恢复后可凭此恢复任务轮询。
     */
    private List<ExcelAiImportResult.TaskLink> tasks = new ArrayList<>();
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
