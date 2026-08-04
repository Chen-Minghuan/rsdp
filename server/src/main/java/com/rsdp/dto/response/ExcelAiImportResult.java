package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
     * 跳过的行数（说明行/重复表头行/已存在且未开启更新）。
     */
    private int skippedCount;

    /**
     * 创建的 RSPU ID 列表。
     */
    private List<String> rspuIds = new ArrayList<>();

    /**
     * 创建的异步任务 ID 列表。
     */
    private List<String> taskIds = new ArrayList<>();

    /**
     * 异步任务与 RSPU 的成对关联（仅有任务的行，与 rspuIds/taskIds 平行数组不同，不错位）。
     */
    private List<TaskLink> tasks = new ArrayList<>();

    /**
     * 失败明细。
     */
    private List<ExcelAiImportFailure> failures = new ArrayList<>();

    /**
     * 异步任务 ↔ RSPU 关联。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TaskLink {

        /**
         * 异步任务 ID。
         */
        private String taskId;

        /**
         * 该任务所属 RSPU ID。
         */
        private String rspuId;
    }
}
