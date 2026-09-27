package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel AI 辅助导入受理结果（阶段 3.2：confirm 异步化）。
 *
 * <p>confirm 接口完成前置校验、批次抢占与异步任务创建后立即返回本对象，
 * 导入本体由异步任务驱动执行；客户端凭 batchId 轮询批次状态获取最终结果。</p>
 *
 * <p>P0-F（v3.3 §50.0 Replay-first）：命中既有 done Attempt 时直接重放
 * （{@code replayed=true}，status=done，携带 outcome / resultSummary），
 * 不创建新执行。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelAiImportSubmitResult {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 批次导入异步任务 ID（task_type=excel_import）。
     */
    private String taskId;

    /**
     * 受理后的批次状态（importing / done（重放））。
     */
    private String status;

    /** Import Attempt ID（P0-F；旧路径无 Attempt 时为 null）。 */
    private String attemptId;

    /** done Attempt 重放时为 true。 */
    private boolean replayed;

    /** Attempt 业务终态（success / partial_success / business_rejected；仅重放 done 时非空）。 */
    private String outcome;

    /** Attempt 物化的结果摘要 JSON（仅重放 done 时非空，§49.2.3 原样返回）。 */
    private String resultSummary;

    public ExcelAiImportSubmitResult(String batchId, String taskId, String status) {
        this.batchId = batchId;
        this.taskId = taskId;
        this.status = status;
    }
}
