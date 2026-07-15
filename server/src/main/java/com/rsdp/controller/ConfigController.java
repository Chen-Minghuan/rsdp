package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.entity.SysConfig;
import com.rsdp.service.ConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 系统配置接口。
 */
@RestController
@RequestMapping("/api/v1/configs")
@RequiredArgsConstructor
@Validated
public class ConfigController {

    private final ConfigService configService;

    /**
     * 读取配置。
     *
     * @param key 配置键
     * @return 配置
     */
    @GetMapping("/{key}")
    public Result<SysConfig> get(@PathVariable @NotBlank(message = "配置键不能为空") String key) {
        return Result.ok(configService.get(key));
    }

    /**
     * 更新配置（仅 ADMIN）。
     *
     * @param key     配置键
     * @param request 配置值
     * @return 更新后的配置
     */
    @PutMapping("/{key}")
    public Result<SysConfig> set(
        @PathVariable @NotBlank(message = "配置键不能为空") String key,
        @RequestBody @Valid ConfigUpdateRequest request) {
        return Result.ok(configService.set(key, request.getConfigValue()));
    }

    /**
     * 配置更新请求。
     */
    @Data
    public static class ConfigUpdateRequest {

        @NotBlank(message = "配置值不能为空")
        private String configValue;
    }
}
