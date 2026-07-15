package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.ProjectRequest;
import com.rsdp.dto.response.ProjectDetailResponse;
import com.rsdp.dto.response.ProjectResponse;
import com.rsdp.service.ProjectService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设计项目接口。
 */
@RestController
@RequestMapping("/api/v1/projects")
@RequiredArgsConstructor
@Validated
public class ProjectController {

    private final ProjectService projectService;

    /**
     * 分页查询项目列表。
     *
     * @param keyword 关键词（可选）
     * @param scope   范围：all=全部（仅 ADMIN 生效），mine=仅自己的（可选）
     * @param page    页码
     * @param size    每页条数
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<ProjectResponse>> list(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String scope,
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "10") long size) {
        return Result.ok(projectService.list(keyword, scope, page, size));
    }

    /**
     * 创建设计项目。
     *
     * @param request 创建请求
     * @return 创建后的项目
     */
    @PostMapping
    public Result<ProjectResponse> create(@RequestBody @Valid ProjectRequest request) {
        return Result.ok(projectService.create(request));
    }

    /**
     * 查询项目详情（含方案列表）。
     *
     * @param projectId 项目 ID
     * @return 项目详情
     */
    @GetMapping("/{projectId}")
    public Result<ProjectDetailResponse> detail(
        @PathVariable @NotBlank(message = "项目 ID 不能为空") String projectId) {
        return Result.ok(projectService.detail(projectId));
    }

    /**
     * 更新设计项目。
     *
     * @param projectId 项目 ID
     * @param request   更新请求
     * @return 更新后的项目
     */
    @PutMapping("/{projectId}")
    public Result<ProjectResponse> update(
        @PathVariable @NotBlank(message = "项目 ID 不能为空") String projectId,
        @RequestBody @Valid ProjectRequest request) {
        return Result.ok(projectService.update(projectId, request));
    }

    /**
     * 软删除设计项目（项目下方案保留，project_id 置空）。
     *
     * @param projectId 项目 ID
     * @return 空结果
     */
    @DeleteMapping("/{projectId}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "项目 ID 不能为空") String projectId) {
        projectService.delete(projectId);
        return Result.ok();
    }
}
