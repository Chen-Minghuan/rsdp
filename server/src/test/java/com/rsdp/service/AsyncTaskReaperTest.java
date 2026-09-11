package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AiRecognition;
import com.rsdp.entity.AsyncTask;
import com.rsdp.entity.RspuMaster;
import com.rsdp.mapper.AiRecognitionMapper;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.mapper.DocumentImportBatchMapper;
import com.rsdp.mapper.ExcelImportBatchMapper;
import com.rsdp.mapper.RspuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AsyncTaskReaper} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class AsyncTaskReaperTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Mock
    private ExcelImportBatchMapper excelImportBatchMapper;

    @Mock
    private DocumentImportBatchMapper documentImportBatchMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private AiRecognitionMapper aiRecognitionMapper;

    @Mock
    private AuditLogService auditLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AsyncTaskReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new AsyncTaskReaper(asyncTaskMapper, excelImportBatchMapper, documentImportBatchMapper,
            rspuMapper, aiRecognitionMapper, auditLogService, objectMapper);
        ReflectionTestUtils.setField(reaper, "pendingTimeoutMs", 600000L);
        ReflectionTestUtils.setField(reaper, "processingTimeoutMs", 1800000L);
        ReflectionTestUtils.setField(reaper, "importBatchTimeoutMs", 7200000L);
        // 默认没有被收割的 product_entry 任务；个别测试覆盖
        lenient().when(asyncTaskMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void reapStaleTasks_shouldResetStaleImportingBatchesToPending() {
        when(excelImportBatchMapper.reapStaleImporting(any())).thenReturn(2);

        reaper.reapStaleTasks();

        // 超时 importing 批次按 import-batch-timeout-ms（默认 2 小时）阈值复位为 pending
        ArgumentCaptor<LocalDateTime> thresholdCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(excelImportBatchMapper).reapStaleImporting(thresholdCaptor.capture());
        assertThat(thresholdCaptor.getValue()).isBetween(
            LocalDateTime.now().minusSeconds(7200 + 60),
            LocalDateTime.now().minusSeconds(7200 - 60));

        // async_task 的 pending / processing 收割照常执行
        verify(asyncTaskMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void reapStaleTasks_shouldNotPropagateWhenBatchReapFails() {
        when(excelImportBatchMapper.reapStaleImporting(any())).thenThrow(new RuntimeException("db down"));

        // 收割器自身失败不能影响调度线程，仅记日志
        assertThatCode(() -> reaper.reapStaleTasks()).doesNotThrowAnyException();
    }

    @Test
    void reapStaleTasks_shouldMarkProductEntryRspuDoubtfulWhenReaped() {
        // 超时 product_entry 任务被收割时，仍卡在 processing 的 RSPU 联动置存疑
        // （status 保持 processing，不置 active，与识别失败兜底同语义），并补 ai_recognition 失败记录
        AsyncTask task = staleProductEntryTask("TASK-1", "RSPU-1", "IMG-1");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(task));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("processing");
        rspu.setReviewStatus("待复核");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(rspuMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        reaper.reapStaleTasks();

        // RSPU 条件置存疑（仅仍 processing 时生效）；status 不翻转，保持 processing
        ArgumentCaptor<UpdateWrapper<RspuMaster>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(rspuMapper).update(isNull(), wrapperCaptor.capture());
        String sqlSet = wrapperCaptor.getValue().getSqlSet();
        assertThat(sqlSet)
            .contains("review_status")
            .contains("review_comment");
        assertThat(java.util.Arrays.stream(sqlSet.split(",")).map(String::trim))
            .noneMatch(setClause -> setClause.startsWith("status="));

        // 补 ai_recognition 失败记录（对齐 saveFailure 写法）
        ArgumentCaptor<AiRecognition> recCaptor = ArgumentCaptor.forClass(AiRecognition.class);
        verify(aiRecognitionMapper).insert(recCaptor.capture());
        AiRecognition rec = recCaptor.getValue();
        assertThat(rec.getRspuId()).isEqualTo("RSPU-1");
        assertThat(rec.getImageId()).isEqualTo("IMG-1");
        assertThat(rec.getTaskId()).isEqualTo("TASK-1");
        assertThat(rec.getStatus()).isEqualTo("failed");
        assertThat(rec.getErrorMessage()).contains("超时");

        // 审计日志：操作人取任务 createdBy
        verify(auditLogService).logReview(eq("rspu_master"), eq("RSPU-1"), any(), any(), eq("user-1"));
    }

    @Test
    void reapStaleTasks_shouldNotTouchRspuThatIsNotProcessing() {
        // RSPU 已被识别链路翻转为 active 时不覆盖后续状态
        AsyncTask task = staleProductEntryTask("TASK-1", "RSPU-1", "IMG-1");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(task));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("active");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);

        reaper.reapStaleTasks();

        verify(rspuMapper, never()).update(isNull(), any(UpdateWrapper.class));
        verify(aiRecognitionMapper, never()).insert(any(AiRecognition.class));
        verify(auditLogService, never()).logReview(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void reapStaleTasks_shouldNotInsertRecognitionWhenConditionalUpdateMisses() {
        // 条件更新未命中（并发下 RSPU 刚被识别线程翻转）时不补识别记录、不写审计
        AsyncTask task = staleProductEntryTask("TASK-1", "RSPU-1", "IMG-1");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(task));

        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-1");
        rspu.setStatus("processing");
        when(rspuMapper.selectById("RSPU-1")).thenReturn(rspu);
        when(rspuMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        reaper.reapStaleTasks();

        verify(aiRecognitionMapper, never()).insert(any(AiRecognition.class));
        verify(auditLogService, never()).logReview(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void reapStaleTasks_shouldNotLinkNonProductEntryTasks() {
        // 查询按 task_type='product_entry' 过滤，无命中任务时不触碰 RSPU
        reaper.reapStaleTasks();

        verify(rspuMapper, never()).selectById(anyString());
        verify(aiRecognitionMapper, never()).insert(any(AiRecognition.class));
        // 批量收割照常执行
        verify(asyncTaskMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void reapStaleTasks_shouldFailDocumentImportBatchWhenReaped() {
        // 超时 document_import 任务被收割时，仍卡在 pending/processing 的批次联动置 failed（3.1）
        AsyncTask task = new AsyncTask();
        task.setTaskId("TASK-D1");
        task.setTaskType("document_import");
        task.setStatus("processing");
        task.setCreatedBy("user-1");
        task.setCreatedAt(LocalDateTime.now().minusHours(2));
        task.setInputData("{\"batchId\":\"BATCH-D1\",\"objectKey\":\"document-imports/BATCH-D1.pdf\"}");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(documentImportBatchMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);

        reaper.reapStaleTasks();

        // 批次条件置 failed（仅 pending/processing 生效），写明收割原因并落完成时间
        ArgumentCaptor<UpdateWrapper<com.rsdp.entity.DocumentImportBatch>> wrapperCaptor =
            ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(documentImportBatchMapper).update(isNull(), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet())
            .contains("status")
            .contains("error_message")
            .contains("completed_at");
        // product_entry 联动链不被误触（该任务 input_data 无 rspuId）
        verify(rspuMapper, never()).selectById(anyString());
    }

    @Test
    void reapStaleTasks_shouldNotTouchDocumentImportBatchWhenUpdateMisses() {
        // 条件更新未命中（批次已被执行线程写入终态）时不覆盖
        AsyncTask task = new AsyncTask();
        task.setTaskId("TASK-D2");
        task.setTaskType("document_import");
        task.setStatus("pending");
        task.setCreatedAt(LocalDateTime.now().minusHours(2));
        task.setInputData("{\"batchId\":\"BATCH-D2\"}");
        when(asyncTaskMapper.selectList(any())).thenReturn(List.of(task));
        when(documentImportBatchMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        assertThatCode(() -> reaper.reapStaleTasks()).doesNotThrowAnyException();
        verify(documentImportBatchMapper).update(isNull(), any(UpdateWrapper.class));
    }

    /** 构造一个超时 product_entry 任务（pending，创建时间远超阈值）。 */
    private AsyncTask staleProductEntryTask(String taskId, String rspuId, String imageId) {
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("product_entry");
        task.setStatus("pending");
        task.setCreatedBy("user-1");
        task.setCreatedAt(LocalDateTime.now().minusHours(2));
        task.setInputData("{\"rspuId\":\"" + rspuId + "\",\"imageId\":\"" + imageId + "\",\"objectKey\":\"images/" + imageId + ".jpg\"}");
        return task;
    }
}
