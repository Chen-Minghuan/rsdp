package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel AI 辅助导入受理结果（阶段 3.2：confirm 异步化）。
 *
 * <p>confirm 接口完成前置校验、批次抢占与异步任务创建后立即返回本对象，
 * 导入本体由异步任务驱动执行；客户端凭 batchId 轮询批次状态获取最终结果。</p>
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
     * 受理后的批次状态（固定为 importing）。
     */
    private String status;
}
