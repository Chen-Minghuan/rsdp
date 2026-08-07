package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 异步任务收割器：兜底清理永久挂起的任务。
 *
 * <p>任务可能因以下原因永远停留在非终态，导致前端轮询永不结束：
 * ① 线程池队列满 AbortPolicy 拒绝投递，任务停在 pending；
 * ② JVM 崩溃/重启，任务停在 processing；
 * ③ 处理过程兜底链自身异常（如 DB 故障时 saveFailure/updateTaskStatus 均失败）。</p>
 *
 * <p>本收割器定时扫描：超时 pending 任务说明从未被认领执行，超时 processing 任务说明
 * 执行线程已消亡（AI 识别正常耗时远低于阈值），均标记为 failed 并写明原因。
 * 使用条件 UPDATE（状态前置校验），不会覆盖并发执行线程刚写入的状态。</p>
 *
 * <p>同时收割 excel_import_batch 中超时 importing 的批次：批次被抢占为 importing 后
 * 若 JVM 崩溃/重启会永久卡死，用户无法重试；超时后复位为 pending。阈值默认 2 小时，
 * 需大于正常导入最坏耗时，避免误收割仍在运行的导入（导入期间批次 updated_at 仅在被抢占
 * 与最终落库时刷新，中间不更新）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskReaper {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ExcelImportBatchMapper excelImportBatchMapper;

    /** pending 超时（毫秒）：超过该时长未被认领视为投递失败 */
    @Value("${rsdp.task.pending-timeout-ms:600000}")
    private long pendingTimeoutMs;

    /** processing 超时（毫秒）：超过该时长未完成视为执行线程消亡 */
    @Value("${rsdp.task.processing-timeout-ms:1800000}")
    private long processingTimeoutMs;

    /** Excel 导入批次 importing 超时（毫秒）：超过该时长未更新视为导入线程消亡，复位 pending 允许重试 */
    @Value("${rsdp.task.import-batch-timeout-ms:7200000}")
    private long importBatchTimeoutMs;

    /**
     * 定时收割超时任务（默认每 10 分钟执行一次，启动 1 分钟后首次执行）。
     */
    @Scheduled(fixedDelayString = "${rsdp.task.reaper-interval-ms:600000}",
        initialDelayString = "${rsdp.task.reaper-initial-delay-ms:60000}")
    public void reapStaleTasks() {
        try {
            LocalDateTime now = LocalDateTime.now();

            int pendingReaped = asyncTaskMapper.update(null, new UpdateWrapper<AsyncTask>()
                .eq("status", "pending")
                .lt("created_at", now.minusSeconds(pendingTimeoutMs / 1000))
                .set("status", "failed")
                .set("progress", 100)
                .set("error_message", "任务投递后长时间未被认领（可能线程池拒绝或进程重启），已被收割任务标记失败，请重试")
                .set("completed_at", now));
            if (pendingReaped > 0) {
                log.warn("收割超时 pending 任务 {} 个（阈值 {}ms）", pendingReaped, pendingTimeoutMs);
            }

            int processingReaped = asyncTaskMapper.update(null, new UpdateWrapper<AsyncTask>()
                .eq("status", "processing")
                .lt("created_at", now.minusSeconds(processingTimeoutMs / 1000))
                .set("status", "failed")
                .set("progress", 100)
                .set("error_message", "任务执行超时（可能进程重启导致执行中断），已被收割任务标记失败，请重试")
                .set("completed_at", now));
            if (processingReaped > 0) {
                log.warn("收割超时 processing 任务 {} 个（阈值 {}ms）", processingReaped, processingTimeoutMs);
            }

            int importBatchReaped = excelImportBatchMapper.reapStaleImporting(
                now.minusSeconds(importBatchTimeoutMs / 1000));
            if (importBatchReaped > 0) {
                log.warn("收割超时 importing 导入批次 {} 个（阈值 {}ms），已复位为 pending 允许重试",
                    importBatchReaped, importBatchTimeoutMs);
            }
        } catch (Exception e) {
            // 收割器自身失败（如 DB 短暂故障）不能影响调度线程，下个周期重试
            log.error("异步任务收割执行失败: {}", e.getMessage());
        }
    }
}
