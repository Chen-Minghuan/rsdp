package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片向量重建任务服务（pgvector 逐图片重建）。
 *
 * <p>通过 async_task 表驱动：{@link #submitRebuildTask} 创建
 * {@code task_type=vector_rebuild} 的 pending 任务并返回 taskId，
 * {@link #executeRebuildTask} 循环调用 {@link VectorBackfillService#backfill}
 * 直至本轮无新增处理（success=0 且 failed=0，即剩余候选全部幂等跳过或已耗尽），
 * 逐轮更新 progress 与 result_data，结束置 done / partial_success（有失败时），
 * 异常置 failed。状态更新仿 {@code AsyncTaskProcessor.updateTaskStatus} 的终态保护。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorRebuildService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final VectorBackfillService vectorBackfillService;
    private final ObjectMapper objectMapper;

    /** 默认批次大小 */
    private static final int DEFAULT_BATCH_SIZE = 500;

    /** 单轮处理上限 */
    private static final int MAX_BATCH_SIZE = 1000;

    /** 每轮进度递增步长（总量未知，按轮次推进，结束置 100） */
    private static final int PROGRESS_STEP = 5;

    /**
     * 提交向量重建异步任务。
     *
     * @param batchSize 每轮回填数量（{@code <=0} 取默认 500，超过 1000 截断为 1000）
     * @param createdBy 创建人
     * @return 任务 ID
     */
    public String submitRebuildTask(int batchSize, String createdBy) {
        if (batchSize <= 0) {
            batchSize = DEFAULT_BATCH_SIZE;
        }
        if (batchSize > MAX_BATCH_SIZE) {
            batchSize = MAX_BATCH_SIZE;
        }
        try {
            AsyncTask task = new AsyncTask();
            task.setTaskId(IdGenerator.taskId());
            task.setTaskType("vector_rebuild");
            task.setStatus("pending");
            task.setProgress(0);
            task.setInputData(objectMapper.writeValueAsString(Map.of("batchSize", batchSize)));
            task.setCreatedBy(createdBy);
            task.setCreatedAt(LocalDateTime.now());
            asyncTaskMapper.insert(task);
            return task.getTaskId();
        } catch (Exception e) {
            log.error("创建向量重建任务失败", e);
            throw new IllegalStateException("创建向量重建任务失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行向量重建任务：循环回填直至候选集全部处理完毕（本轮无新增成功/失败）。
     *
     * @param taskId 任务 ID
     */
    public void executeRebuildTask(String taskId) {
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            log.warn("向量重建任务不存在，taskId={}", taskId);
            return;
        }
        int batchSize = resolveBatchSize(task);
        try {
            updateTaskStatus(taskId, "processing", 1, null, null);

            int totalSuccess = 0;
            int totalFailed = 0;
            int scannedPages = 0;
            while (true) {
                VectorBackfillService.BackfillResult round = vectorBackfillService.backfill(batchSize);
                scannedPages++;
                totalSuccess += round.successCount();
                totalFailed += round.failedCount();
                updateTaskStatus(taskId, "processing", Math.min(95, scannedPages * PROGRESS_STEP),
                    buildResultData(scannedPages, totalSuccess, totalFailed), null);
                log.info("向量重建第 {} 轮完成：success={}, failed={}, taskId={}",
                    scannedPages, round.successCount(), round.failedCount(), taskId);
                if (round.successCount() == 0 && round.failedCount() == 0) {
                    break; // 本轮无新增处理：剩余候选均为幂等跳过或候选集已耗尽
                }
            }

            if (totalFailed > 0) {
                updateTaskStatus(taskId, "partial_success", 100,
                    buildResultData(scannedPages, totalSuccess, totalFailed),
                    totalFailed + " 张图片重建失败，可重跑");
            } else {
                updateTaskStatus(taskId, "done", 100,
                    buildResultData(scannedPages, totalSuccess, totalFailed), null);
            }
        } catch (Exception e) {
            log.error("向量重建任务执行失败，taskId={}", taskId, e);
            safeUpdateTaskStatus(taskId, "failed", 100, null,
                "向量重建任务执行失败: " + e.getMessage());
        }
    }

    /**
     * 从任务 input_data 解析每轮回填数量；缺失/非法时取默认值。
     */
    private int resolveBatchSize(AsyncTask task) {
        try {
            if (task.getInputData() == null || task.getInputData().isBlank()) {
                return DEFAULT_BATCH_SIZE;
            }
            JsonNode node = objectMapper.readTree(task.getInputData());
            int batchSize = node.path("batchSize").asInt(DEFAULT_BATCH_SIZE);
            if (batchSize <= 0) {
                return DEFAULT_BATCH_SIZE;
            }
            return Math.min(batchSize, MAX_BATCH_SIZE);
        } catch (Exception e) {
            log.warn("解析向量重建任务 batchSize 失败，使用默认值 {}，taskId={}: {}",
                DEFAULT_BATCH_SIZE, task.getTaskId(), e.getMessage());
            return DEFAULT_BATCH_SIZE;
        }
    }

    /**
     * 构造 result_data JSON：{scannedPages, totalSuccess, totalFailed}。
     */
    private String buildResultData(int scannedPages, int totalSuccess, int totalFailed) {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("scannedPages", scannedPages);
            data.put("totalSuccess", totalSuccess);
            data.put("totalFailed", totalFailed);
            return objectMapper.writeValueAsString(data);
        } catch (Exception e) {
            log.warn("构造向量重建结果 JSON 失败: {}", e.getMessage());
            return null;
        }
    }

    private void safeUpdateTaskStatus(String taskId, String status, int progress, String resultData, String errorMessage) {
        try {
            updateTaskStatus(taskId, status, progress, resultData, errorMessage);
        } catch (Exception ex) {
            log.error("更新向量重建任务状态异常，taskId={}", taskId, ex);
        }
    }

    /**
     * 更新任务状态（仿 AsyncTaskProcessor.updateTaskStatus：终态保护，终态时记录完成时间）。
     */
    private void updateTaskStatus(String taskId, String status, int progress, String resultData, String errorMessage) {
        AsyncTask current = asyncTaskMapper.selectById(taskId);
        if (current == null) {
            log.warn("任务不存在，taskId={}", taskId);
            return;
        }
        // 终态保护：已进入终态的任务不允许被非终态（如迟到的进度更新）覆盖，防止状态回退
        if (isTerminalStatus(current.getStatus()) && !isTerminalStatus(status)) {
            log.warn("任务已处于终态 {}，忽略非终态更新 {}，taskId={}", current.getStatus(), status, taskId);
            return;
        }
        current.setStatus(status);
        current.setProgress(progress);
        current.setResultData(resultData);
        current.setErrorMessage(errorMessage);
        if (isTerminalStatus(status)) {
            current.setCompletedAt(LocalDateTime.now());
        }
        asyncTaskMapper.updateById(current);
    }

    private boolean isTerminalStatus(String status) {
        return "done".equals(status) || "failed".equals(status) || "partial_success".equals(status);
    }
}
