package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaskService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private AsyncTaskMapper asyncTaskMapper;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private TaskService taskService;

    private AsyncTask sampleTask() {
        AsyncTask task = new AsyncTask();
        task.setTaskId("TASK-1");
        task.setTaskType("image_entry");
        task.setStatus("done");
        task.setCreatedBy("admin");
        task.setCreatedAt(LocalDateTime.of(2026, 8, 10, 10, 0, 0));
        task.setCompletedAt(LocalDateTime.of(2026, 8, 10, 10, 0, 12));
        task.setResultData("{\"rspuId\":\"RSPU-9\"}");
        return task;
    }

    @Test
    void listRecentTasks_shouldMapDurationAndRspuId() {
        when(asyncTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(sampleTask()));

        List<Map<String, Object>> result = taskService.listRecentTasks(5);

        assertThat(result).hasSize(1);
        Map<String, Object> item = result.get(0);
        assertThat(item.get("taskId")).isEqualTo("TASK-1");
        assertThat(item.get("durationSeconds")).isEqualTo(12L);
        assertThat(item.get("rspuId")).isEqualTo("RSPU-9");
        assertThat(item.get("createdBy")).isEqualTo("admin");
    }

    @Test
    void listRecentTasks_unfinishedTask_shouldReturnNullDuration() {
        AsyncTask task = sampleTask();
        task.setCompletedAt(null);
        task.setResultData(null);
        when(asyncTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(task));

        List<Map<String, Object>> result = taskService.listRecentTasks(5);

        assertThat(result.get(0).get("durationSeconds")).isNull();
        assertThat(result.get(0).get("rspuId")).isNull();
    }

    @Test
    void listRecentTasks_shouldClampSizeToTwenty() {
        when(asyncTaskMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        taskService.listRecentTasks(100);

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(asyncTaskMapper).selectList(captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("LIMIT 20");
    }
}
