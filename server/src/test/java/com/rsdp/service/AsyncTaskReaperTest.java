package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.mapper.AsyncTaskMapper;import com.rsdp.mapper.ExcelImportBatchMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
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

    private AsyncTaskReaper reaper;

    @BeforeEach
    void setUp() {
        reaper = new AsyncTaskReaper(asyncTaskMapper, excelImportBatchMapper);
        ReflectionTestUtils.setField(reaper, "pendingTimeoutMs", 600000L);
        ReflectionTestUtils.setField(reaper, "processingTimeoutMs", 1800000L);
        ReflectionTestUtils.setField(reaper, "importBatchTimeoutMs", 7200000L);
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
}
