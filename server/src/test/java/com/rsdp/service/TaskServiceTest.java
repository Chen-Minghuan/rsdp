package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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

    // ==================== 批量任务状态查询 ====================

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateWithRoles(String username, String... roles) {
        SecurityContextHolder.clearContext();
        var user = User.withUsername(username).password("").roles(roles).build();
        var auth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private AsyncTask taskOf(String taskId, String createdBy, String status) {
        AsyncTask task = new AsyncTask();
        task.setTaskId(taskId);
        task.setTaskType("image_entry");
        task.setStatus(status);
        task.setCreatedBy(createdBy);
        task.setCreatedAt(LocalDateTime.of(2026, 9, 11, 10, 0, 0));
        return task;
    }

    @Test
    @SuppressWarnings("unchecked")
    void listTaskStatuses_shouldReturnVisibleTasksInRequestOrder() {
        authenticateWithRoles("alice", "DESIGNER");
        when(asyncTaskMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
            taskOf("TASK-2", "alice", "done"),
            taskOf("TASK-1", "alice", "processing")
        ));

        Map<String, Object> result = taskService.listTaskStatuses(List.of("TASK-1", "TASK-2", "TASK-1", "TASK-X"));

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks).extracting(t -> t.get("taskId")).containsExactly("TASK-1", "TASK-2");
        assertThat(tasks.get(0).get("status")).isEqualTo("processing");
        assertThat((List<String>) result.get("skippedIds")).containsExactly("TASK-X");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listTaskStatuses_nonPlatformStaff_shouldSkipOthersTasks() {
        authenticateWithRoles("alice", "DESIGNER");
        when(asyncTaskMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
            taskOf("TASK-1", "alice", "done"),
            taskOf("TASK-2", "bob", "done")
        ));

        Map<String, Object> result = taskService.listTaskStatuses(List.of("TASK-1", "TASK-2"));

        List<Map<String, Object>> tasks = (List<Map<String, Object>>) result.get("tasks");
        assertThat(tasks).extracting(t -> t.get("taskId")).containsExactly("TASK-1");
        assertThat((List<String>) result.get("skippedIds")).containsExactly("TASK-2");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listTaskStatuses_platformStaff_shouldSeeAllTasks() {
        authenticateWithRoles("admin", "ADMIN");
        when(asyncTaskMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
            taskOf("TASK-1", "alice", "done"),
            taskOf("TASK-2", "bob", "done")
        ));

        Map<String, Object> result = taskService.listTaskStatuses(List.of("TASK-1", "TASK-2"));

        assertThat((List<Map<String, Object>>) result.get("tasks")).hasSize(2);
        assertThat((List<String>) result.get("skippedIds")).isEmpty();
    }

    @Test
    void listTaskStatuses_overLimit_shouldReject() {
        List<String> ids = IntStream.range(0, TaskService.BATCH_QUERY_MAX_IDS + 1)
            .mapToObj(i -> "TASK-" + i)
            .toList();

        assertThatThrownBy(() -> taskService.listTaskStatuses(ids))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("100");
    }

    @Test
    @SuppressWarnings("unchecked")
    void listTaskStatuses_emptyIds_shouldReturnEmptyWithoutQuery() {
        Map<String, Object> result = taskService.listTaskStatuses(List.of());

        assertThat((List<Map<String, Object>>) result.get("tasks")).isEmpty();
        assertThat((List<String>) result.get("skippedIds")).isEmpty();
    }
}
