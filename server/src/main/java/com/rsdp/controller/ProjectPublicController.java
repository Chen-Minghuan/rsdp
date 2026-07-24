package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.ProjectShareResponse;
import com.rsdp.service.ProjectShareService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 项目画布分享公开接口（免登录只读，/api/v1/public/** 由 SecurityConfig 放行）。
 */
@RestController
@RequestMapping("/api/v1/public/projects")
@RequiredArgsConstructor
@Validated
public class ProjectPublicController {

    private final ProjectShareService projectShareService;

    /**
     * 获取项目分享公开视图（校验分享开关 + 过期时间）。
     *
     * @param projectId 项目 ID
     * @return 分享视图
     */
    @GetMapping("/{projectId}")
    public Result<ProjectShareResponse> getSharedProject(
        @PathVariable @NotBlank(message = "项目 ID 不能为空") String projectId) {
        return Result.ok(projectShareService.getSharedProject(projectId));
    }
}
