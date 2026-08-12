package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.entity.AsyncTask;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.AsyncTaskMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 最近任务列表（工作台「识别任务队列」）。
     *
     * <p>平台运营人员可见全部，其他用户仅见自己创建的任务（与 getTaskStatus 同口径）。</p>
     *
     * @param size 条数（1~20）
     * @return 最近任务列表（含耗时秒数与关联产品 ID）
     */
    public List<Map<String, Object>> listRecentTasks(int size) {
        int safeSize = Math.max(1, Math.min(size, 20));
        QueryWrapper<AsyncTask> wrapper = new QueryWrapper<AsyncTask>()
            .orderByDesc("created_at")
            .last("LIMIT " + safeSize);
        if (!SecurityOperatorContext.isPlatformStaff()) {
            wrapper.eq("created_by", SecurityOperatorContext.currentUsername());
        }
        return asyncTaskMapper.selectList(wrapper).stream()
            .map(this::toRecentItem)
            .toList();
    }

    private Map<String, Object> toRecentItem(AsyncTask task) {
        Map<String, Object> item = new HashMap<>();
        item.put("taskId", task.getTaskId());
        item.put("taskType", task.getTaskType());
        item.put("status", task.getStatus());
        item.put("createdBy", task.getCreatedBy() != null ? task.getCreatedBy() : "");
        item.put("createdAt", task.getCreatedAt());
        item.put("completedAt", task.getCompletedAt());
        Long durationSeconds = null;
        if (task.getCreatedAt() != null && task.getCompletedAt() != null) {
            durationSeconds = Duration.between(task.getCreatedAt(), task.getCompletedAt()).toSeconds();
        }
        item.put("durationSeconds", durationSeconds);
        item.put("rspuId", extractRspuId(task.getResultData()));
        return item;
    }

    /** 从任务结果 JSON 提取关联产品 ID（用于工作台行点击跳产品详情）。 */
    private String extractRspuId(String resultData) {
        if (resultData == null || resultData.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(resultData).path("rspuId");
            return node.isTextual() ? node.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
