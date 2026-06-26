package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.service.TaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 异步任务相关接口。
 */
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;

    /**
     * 查询异步任务状态。
     *
     * @param taskId 任务 ID
     * @return 任务状态、进度、结果
     */
    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> getTask(@PathVariable String taskId) {
        return Result.ok(taskService.getTaskStatus(taskId));
    }
}
