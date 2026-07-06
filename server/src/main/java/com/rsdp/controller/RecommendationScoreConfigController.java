package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RecommendationScoreConfigCreateRequest;
import com.rsdp.dto.request.RecommendationScoreConfigUpdateRequest;
import com.rsdp.dto.response.RecommendationScoreConfigResponse;
import com.rsdp.service.RecommendationScoreConfigService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 推荐打分配置接口。
 */
@RestController
@RequestMapping("/api/v1/recommendation-score-configs")
@RequiredArgsConstructor
@Validated
public class RecommendationScoreConfigController {

    private final RecommendationScoreConfigService recommendationScoreConfigService;

    /**
     * 查询所有配置。
     *
     * @return 配置列表
     */
    @GetMapping
    public Result<List<RecommendationScoreConfigResponse>> list() {
        return Result.ok(recommendationScoreConfigService.list());
    }

    /**
     * 查询默认配置。
     *
     * @return 默认配置
     */
    @GetMapping("/default")
    public Result<RecommendationScoreConfigResponse> getDefault() {
        return Result.ok(recommendationScoreConfigService.getDefault());
    }

    /**
     * 查询配置详情。
     *
     * @param configId 配置 ID
     * @return 配置详情
     */
    @GetMapping("/{configId}")
    public Result<RecommendationScoreConfigResponse> get(
        @PathVariable @NotBlank(message = "配置 ID 不能为空") String configId) {
        return Result.ok(recommendationScoreConfigService.getById(configId));
    }

    /**
     * 创建配置。
     *
     * @param request 创建请求
     * @return 创建后的配置
     */
    @PostMapping
    public Result<RecommendationScoreConfigResponse> create(
        @RequestBody @Valid RecommendationScoreConfigCreateRequest request) {
        return Result.ok(recommendationScoreConfigService.create(request));
    }

    /**
     * 更新配置。
     *
     * @param configId 配置 ID
     * @param request  更新请求
     * @return 更新后的配置
     */
    @PutMapping("/{configId}")
    public Result<RecommendationScoreConfigResponse> update(
        @PathVariable @NotBlank(message = "配置 ID 不能为空") String configId,
        @RequestBody @Valid RecommendationScoreConfigUpdateRequest request) {
        return Result.ok(recommendationScoreConfigService.update(configId, request));
    }

    /**
     * 删除配置。
     *
     * @param configId 配置 ID
     * @return 空结果
     */
    @DeleteMapping("/{configId}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "配置 ID 不能为空") String configId) {
        recommendationScoreConfigService.delete(configId);
        return Result.ok();
    }
}
