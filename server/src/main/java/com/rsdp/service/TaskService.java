package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 异步任务查询服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final AsyncTaskMapper asyncTaskMapper;
    private final ObjectMapper objectMapper;

    /**
     * 查询任务状态及结果。
     *
     * @param taskId 任务 ID
     * @return 任务状态、进度、结果数据
     */
    public Map<String, Object> getTaskStatus(String taskId) {
        AsyncTask task = asyncTaskMapper.selectById(taskId);
        if (task == null) {
            throw new ResourceNotFoundException("任务不存在: " + taskId);
        }

        // 任务归属校验：平台运营人员可查看任意任务，其他用户只能查看自己创建的任务
        if (!SecurityOperatorContext.isPlatformStaff()) {
            String currentUser = SecurityOperatorContext.currentUsername();
            String creator = task.getCreatedBy();
            if (creator == null || !creator.equals(currentUser)) {
                throw new ResourceNotFoundException("任务不存在: " + taskId);
            }
        }

        Object resultData = null;
        if (task.getResultData() != null && !task.getResultData().isBlank()) {
            try {
                resultData = objectMapper.readValue(task.getResultData(), Object.class);
            } catch (Exception e) {
                log.warn("任务结果 JSON 解析失败，taskId={}", taskId, e);
                resultData = task.getResultData();
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("taskId", task.getTaskId());
        result.put("taskType", task.getTaskType());
        result.put("status", task.getStatus());
        result.put("progress", task.getProgress());
        result.put("result", resultData != null ? resultData : Map.of());
        result.put("errorMessage", task.getErrorMessage() != null ? task.getErrorMessage() : "");
        result.put("createdAt", task.getCreatedAt());
        result.put("completedAt", task.getCompletedAt());
        return result;
    }
}
