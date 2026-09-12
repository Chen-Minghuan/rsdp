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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

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

        return toStatusMap(task);
    }

    /** 批量任务状态查询的 ids 数量上限（防滥用）。 */
    public static final int BATCH_QUERY_MAX_IDS = 100;

    /**
     * 批量查询任务状态（前端一轮一请求，消除逐任务轮询风暴）。
     *
     * <p>归属校验与 {@link #getTaskStatus} 同口径：非平台运营只能看自己创建的任务；
     * 不存在或无权限的 id 不整单报错，而是放入 {@code skippedIds} 由前端按「进度查询异常」口径展示。</p>
     *
     * @param taskIds 任务 ID 列表（去重后数量不能超过 {@link #BATCH_QUERY_MAX_IDS}）
     * @return { tasks: 可见任务状态列表（按请求顺序）, skippedIds: 不存在或无权限的 id }
     */
    public Map<String, Object> listTaskStatuses(List<String> taskIds) {
        // 去重并保持请求顺序
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(taskIds));
        if (ids.size() > BATCH_QUERY_MAX_IDS) {
            throw new IllegalArgumentException("单次最多查询 " + BATCH_QUERY_MAX_IDS + " 个任务");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("tasks", List.of());
        result.put("skippedIds", List.of());
        if (ids.isEmpty()) {
            return result;
        }

        Map<String, AsyncTask> taskById = asyncTaskMapper.selectBatchIds(ids).stream()
            .collect(Collectors.toMap(AsyncTask::getTaskId, Function.identity()));
        boolean platformStaff = SecurityOperatorContext.isPlatformStaff();
        String currentUser = SecurityOperatorContext.currentUsername();

        List<Map<String, Object>> tasks = new ArrayList<>();
        List<String> skippedIds = new ArrayList<>();
        for (String id : ids) {
            AsyncTask task = taskById.get(id);
            if (task == null) {
                skippedIds.add(id);
                continue;
            }
            String creator = task.getCreatedBy();
            if (!platformStaff && (creator == null || !creator.equals(currentUser))) {
                skippedIds.add(id);
                continue;
            }
            tasks.add(toStatusMap(task));
        }
        result.put("tasks", tasks);
        result.put("skippedIds", skippedIds);
        return result;
    }

    /** 任务实体 → 状态响应 Map（单个/批量查询共用）。 */
    private Map<String, Object> toStatusMap(AsyncTask task) {
        Object resultData = null;
        if (task.getResultData() != null && !task.getResultData().isBlank()) {
            try {
                resultData = objectMapper.readValue(task.getResultData(), Object.class);
            } catch (Exception e) {
                log.warn("任务结果 JSON 解析失败，taskId={}", task.getTaskId(), e);
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
