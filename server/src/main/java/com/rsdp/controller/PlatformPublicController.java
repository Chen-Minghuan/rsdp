package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformHomeResponse;
import com.rsdp.service.PlatformPublicService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 官网公开读取接口（免登录，/api/v1/public/** 由 SecurityConfig 放行）。
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Validated
public class PlatformPublicController {

    private final PlatformPublicService platformPublicService;

    /**
     * 首页聚合：启用 Banner + 落地案例 + 产品定制。
     *
     * @return 首页聚合数据
     */
    @GetMapping("/home")
    public Result<PlatformHomeResponse> home() {
        return Result.ok(platformPublicService.home());
    }

    /**
     * 按编码读取内容配置（服务协议/客服咨询等）。
     *
     * @param code 内容编码
     * @return 内容配置
     */
    @GetMapping("/content/{code}")
    public Result<PlatformContentResponse> getContentByCode(
        @PathVariable @NotBlank(message = "内容编码不能为空") String code) {
        return Result.ok(platformPublicService.getContentByCode(code));
    }
}
