package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.SchemeShareResponse;
import com.rsdp.service.SchemeShareService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 方案分享公开接口（免登录只读，/api/v1/public/** 由 SecurityConfig 放行）。
 */
@RestController
@RequestMapping("/api/v1/public/schemes")
@RequiredArgsConstructor
@Validated
public class SchemePublicController {

    private final SchemeShareService schemeShareService;

    /**
     * 获取方案分享公开视图（校验分享开关 + 过期时间，过期时间为空=永久有效）。
     *
     * @param schemeId 方案 ID
     * @return 分享视图
     */
    @GetMapping("/{schemeId}")
    public Result<SchemeShareResponse> getSharedScheme(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId) {
        return Result.ok(schemeShareService.getSharedScheme(schemeId));
    }
}
