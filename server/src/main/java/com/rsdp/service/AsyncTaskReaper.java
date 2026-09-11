package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.DocumentImportBatchMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

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
 * <p>product_entry 任务被收割时联动处理其 RSPU：正常识别成功链路会把 RSPU 从
 * processing（识别中）翻转为 active，任务被收割则无人翻转，产品会永久卡在识别中。
 * 收割前将仍停留在 processing 的 RSPU 条件置为存疑（status 保持 processing，
 * 不置 active，与识别失败兜底同语义；官网/检索只消费 active），并补一条
 * ai_recognition 失败记录留档。后续通过重新识别成功或人工复核确认翻转为 active。</p>
 *
 * <p>同时收割 excel_import_batch 中超时 importing 的批次：批次被抢占为 importing 后
 * 若 JVM 崩溃/重启会永久卡死，用户无法重试；超时后复位为 pending。阈值默认 2 小时，
 * 需大于正常导入最坏耗时，避免误收割仍在运行的导入（3.2 起导入行循环逐行刷新
 * 批次 updated_at 心跳，只有导入线程真正消亡才会超时）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsyncTaskReaper {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ExcelImportBatchMapper excelImportBatchMapper;
    private final DocumentImportBatchMapper documentImportBatchMapper;
    private final RspuMapper rspuMapper;
    private final AiRecognitionMapper aiRecognitionMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

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
            LocalDateTime pendingThreshold = now.minusSeconds(pendingTimeoutMs / 1000);
            LocalDateTime processingThreshold = now.minusSeconds(processingTimeoutMs / 1000);

            // product_entry 任务收割前联动：仍卡在 processing 的 RSPU 置存疑（status 保持 processing）
            linkReapedProductEntryRspu(pendingThreshold, processingThreshold);

            // document_import 任务收割前联动：仍卡在 pending/processing 的批次置 failed（批次不会永久卡死）
            linkReapedDocumentImportBatch(now, pendingThreshold, processingThreshold);

            int pendingReaped = asyncTaskMapper.update(null, new UpdateWrapper<AsyncTask>()
                .eq("status", "pending")
                .lt("created_at", pendingThreshold)
                .set("status", "failed")
                .set("progress", 100)
                .set("error_message", "任务投递后长时间未被认领（可能线程池拒绝或进程重启），已被收割任务标记失败，请重试")
                .set("completed_at", now));
            if (pendingReaped > 0) {
                log.warn("收割超时 pending 任务 {} 个（阈值 {}ms）", pendingReaped, pendingTimeoutMs);
            }

            int processingReaped = asyncTaskMapper.update(null, new UpdateWrapper<AsyncTask>()
                .eq("status", "processing")
                .lt("created_at", processingThreshold)
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

    /**
     * 找出将被本周期收割的 document_import 任务，逐个联动其批次置 failed。
     *
     * <p>与下方批量 UPDATE 相同的超时口径；批次收割用条件 UPDATE（仅 pending/processing
     * 可置 failed），不覆盖执行线程刚写入的终态。单个任务联动失败不影响其余任务与批量收割。</p>
     *
     * @param now                 当前时间
     * @param pendingThreshold    pending 超时阈值时间
     * @param processingThreshold processing 超时阈值时间
     */
    private void linkReapedDocumentImportBatch(LocalDateTime now, LocalDateTime pendingThreshold,
                                               LocalDateTime processingThreshold) {
        List<AsyncTask> staleImportTasks = asyncTaskMapper.selectList(new QueryWrapper<AsyncTask>()
            .eq("task_type", "document_import")
            .and(w -> w
                .and(q -> q.eq("status", "pending").lt("created_at", pendingThreshold))
                .or(q -> q.eq("status", "processing").lt("created_at", processingThreshold))));
        if (staleImportTasks == null || staleImportTasks.isEmpty()) {
            return;
        }
        for (AsyncTask task : staleImportTasks) {
            try {
                String batchId = extractInputField(task.getInputData(), "batchId");
                if (!StringUtils.hasText(batchId)) {
                    continue;
                }
                int updated = documentImportBatchMapper.update(null,
                    new UpdateWrapper<com.rsdp.entity.DocumentImportBatch>()
                        .eq("batch_id", batchId)
                        .in("status", "pending", "processing")
                        .set("status", "failed")
                        .set("error_message", "导入任务超时未执行（可能进程重启导致中断），已被收割任务标记失败，请重新上传")
                        .set("completed_at", now)
                        .set("updated_at", now));
                if (updated > 0) {
                    log.warn("收割 document_import 任务联动批次置 failed，taskId={}，batchId={}",
                        task.getTaskId(), batchId);
                }
            } catch (Exception e) {
                log.error("收割任务联动文档导入批次置失败异常，taskId={}: {}", task.getTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 找出将被本周期收割的 product_entry 任务，逐个联动其 RSPU 置存疑。
     *
     * <p>与下方批量 UPDATE 相同的超时口径；单个任务联动失败不影响其余任务与批量收割。</p>
     *
     * @param pendingThreshold    pending 超时阈值时间
     * @param processingThreshold processing 超时阈值时间
     */
    private void linkReapedProductEntryRspu(LocalDateTime pendingThreshold, LocalDateTime processingThreshold) {
        List<AsyncTask> staleEntryTasks = asyncTaskMapper.selectList(new QueryWrapper<AsyncTask>()
            .eq("task_type", "product_entry")
            .and(w -> w
                .and(q -> q.eq("status", "pending").lt("created_at", pendingThreshold))
                .or(q -> q.eq("status", "processing").lt("created_at", processingThreshold))));
        if (staleEntryTasks == null || staleEntryTasks.isEmpty()) {
            return;
        }
        for (AsyncTask task : staleEntryTasks) {
            try {
                markRspuDoubtfulForReapedTask(task);
            } catch (Exception e) {
                log.error("收割任务联动 RSPU 置存疑失败，taskId={}: {}", task.getTaskId(), e.getMessage());
            }
        }
    }

    /**
     * 单个被收割 product_entry 任务的 RSPU 联动：仅当 RSPU 仍是 processing 时
     * 条件置存疑（status 保持 processing，不覆盖并发写入的后续状态），并补 ai_recognition 失败记录。
     *
     * @param task 被收割的任务（input_data 内含 rspuId/imageId）
     */
    private void markRspuDoubtfulForReapedTask(AsyncTask task) {
        String rspuId = extractInputField(task.getInputData(), "rspuId");
        if (!StringUtils.hasText(rspuId)) {
            return;
        }
        RspuMaster rspu = rspuMapper.selectById(rspuId);
        if (rspu == null || !"processing".equals(rspu.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        // 条件更新：仅当 RSPU 仍为 processing 时置存疑（status 不翻转），避免覆盖识别线程刚写入的后续状态
        int updated = rspuMapper.update(null, new UpdateWrapper<RspuMaster>()
            .eq("rspu_id", rspuId)
            .eq("status", "processing")
            .set("review_status", "存疑")
            .set("review_comment", "识别任务超时未执行，可重新识别")
            .set("updated_at", now));
        if (updated == 0) {
            return;
        }

        // 补 ai_recognition 失败记录（字段对齐 AiRecognitionPersistenceService.saveFailure 写法）
        AiRecognition rec = new AiRecognition();
        rec.setRecognitionId(IdGenerator.recognitionId());
        rec.setImageId(extractInputField(task.getInputData(), "imageId"));
        rec.setRspuId(rspuId);
        rec.setTaskId(task.getTaskId());
        rec.setRecognitionType("label");
        rec.setEndpoint("/chat/completions");
        rec.setStatus("failed");
        rec.setProcessingTimeMs(0);
        rec.setErrorMessage("识别任务超时未执行，被收割任务标记失败，可重新识别");
        rec.setCreatedAt(now);
        aiRecognitionMapper.insert(rec);

        RspuMaster newSnapshot = new RspuMaster();
        org.springframework.beans.BeanUtils.copyProperties(rspu, newSnapshot);
        newSnapshot.setStatus("processing");
        newSnapshot.setReviewStatus("存疑");
        newSnapshot.setReviewComment("识别任务超时未执行，可重新识别");
        newSnapshot.setUpdatedAt(now);
        String operator = StringUtils.hasText(task.getCreatedBy()) ? task.getCreatedBy() : "system";
        auditLogService.logReview("rspu_master", rspuId, rspu, newSnapshot, operator);
        log.info("收割 product_entry 任务联动 RSPU 置存疑，taskId={}，rspuId={}", task.getTaskId(), rspuId);
    }

    /**
     * 从任务 input_data JSON 中提取字符串字段。
     *
     * @param inputData 任务输入 JSON
     * @param field     字段名
     * @return 字段值；缺失或解析失败返回 null
     */
    private String extractInputField(String inputData, String field) {
        if (!StringUtils.hasText(inputData)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(inputData).get(field);
            return node != null && node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            log.warn("解析任务 input_data 字段 {} 失败: {}", field, e.getMessage());
            return null;
        }
    }
}
