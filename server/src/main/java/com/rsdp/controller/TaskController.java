package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.service.TaskService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 异步任务相关接口。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
@Validated
public class TaskController {

    private final TaskService taskService;

    /**
     * 查询异步任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务状态、进度、结果
     */
    @GetMapping("/{taskId}")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> getTask(@PathVariable @NotBlank(message = "任务 ID 不能为空") String taskId) {
        return Result.ok(taskService.getTaskStatus(taskId));
    }

    /**
     * 最近任务列表（工作台「识别任务队列」；精确路径优先于 /{taskId} 模板）。
     *
     * @param size 条数（默认 5，上限 20）
     * @return 最近任务列表
     */
    @GetMapping("/recent")
    @PreAuthorize("isAuthenticated()")
    public Result<List<Map<String, Object>>> recentTasks(
        @RequestParam(defaultValue = "5") int size) {
        return Result.ok(taskService.listRecentTasks(size));
    }
}
