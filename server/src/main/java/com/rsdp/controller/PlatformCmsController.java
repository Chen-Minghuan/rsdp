package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.PlatformBannerRequest;
import com.rsdp.dto.request.PlatformCaseRequest;
import com.rsdp.dto.request.PlatformContentRequest;
import com.rsdp.dto.request.PlatformCustomDictRequest;
import com.rsdp.dto.request.PlatformCustomizedRequest;
import com.rsdp.dto.response.PlatformBannerResponse;
import com.rsdp.dto.response.PlatformCaseResponse;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformCustomDictResponse;
import com.rsdp.dto.response.PlatformCustomizedResponse;
import com.rsdp.service.PlatformCmsService;
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
 * 官网 CMS 管理端接口（Banner/案例/内容/自定义字典/产品定制）。
 *
 * <p>由 SecurityConfig 限定 ADMIN/EDITOR 角色。</p>
 */
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Validated
public class PlatformCmsController {

    private final PlatformCmsService platformCmsService;

    // ==================== Banner ====================

    /**
     * 查询 Banner 列表。
     */
    @GetMapping("/banners")
    public Result<List<PlatformBannerResponse>> listBanners() {
        return Result.ok(platformCmsService.listBanners());
    }

    /**
     * 创建 Banner。
     */
    @PostMapping("/banners")
    public Result<PlatformBannerResponse> createBanner(@RequestBody @Valid PlatformBannerRequest request) {
        return Result.ok(platformCmsService.createBanner(request));
    }

    /**
     * 更新 Banner。
     */
    @PutMapping("/banners/{bannerId}")
    public Result<PlatformBannerResponse> updateBanner(
        @PathVariable @NotBlank(message = "Banner ID 不能为空") String bannerId,
        @RequestBody @Valid PlatformBannerRequest request) {
        return Result.ok(platformCmsService.updateBanner(bannerId, request));
    }

    /**
     * 删除 Banner。
     */
    @DeleteMapping("/banners/{bannerId}")
    public Result<Void> deleteBanner(@PathVariable @NotBlank(message = "Banner ID 不能为空") String bannerId) {
        platformCmsService.deleteBanner(bannerId);
        return Result.ok();
    }

    // ==================== 落地案例 ====================

    /**
     * 查询案例列表。
     */
    @GetMapping("/cases")
    public Result<List<PlatformCaseResponse>> listCases() {
        return Result.ok(platformCmsService.listCases());
    }

    /**
     * 创建案例。
     */
    @PostMapping("/cases")
    public Result<PlatformCaseResponse> createCase(@RequestBody @Valid PlatformCaseRequest request) {
        return Result.ok(platformCmsService.createCase(request));
    }

    /**
     * 更新案例。
     */
    @PutMapping("/cases/{caseId}")
    public Result<PlatformCaseResponse> updateCase(
        @PathVariable @NotBlank(message = "案例 ID 不能为空") String caseId,
        @RequestBody @Valid PlatformCaseRequest request) {
        return Result.ok(platformCmsService.updateCase(caseId, request));
    }

    /**
     * 删除案例。
     */
    @DeleteMapping("/cases/{caseId}")
    public Result<Void> deleteCase(@PathVariable @NotBlank(message = "案例 ID 不能为空") String caseId) {
        platformCmsService.deleteCase(caseId);
        return Result.ok();
    }

    // ==================== 内容配置 ====================

    /**
     * 查询内容配置列表。
     */
    @GetMapping("/contents")
    public Result<List<PlatformContentResponse>> listContents() {
        return Result.ok(platformCmsService.listContents());
    }

    /**
     * 创建内容配置。
     */
    @PostMapping("/contents")
    public Result<PlatformContentResponse> createContent(@RequestBody @Valid PlatformContentRequest request) {
        return Result.ok(platformCmsService.createContent(request));
    }

    /**
     * 更新内容配置（code 不可修改）。
     */
    @PutMapping("/contents/{contentId}")
    public Result<PlatformContentResponse> updateContent(
        @PathVariable @NotBlank(message = "内容 ID 不能为空") String contentId,
        @RequestBody @Valid PlatformContentRequest request) {
        return Result.ok(platformCmsService.updateContent(contentId, request));
    }

    /**
     * 删除内容配置。
     */
    @DeleteMapping("/contents/{contentId}")
    public Result<Void> deleteContent(@PathVariable @NotBlank(message = "内容 ID 不能为空") String contentId) {
        platformCmsService.deleteContent(contentId);
        return Result.ok();
    }

    // ==================== 自定义字典 ====================

    /**
     * 查询自定义字典列表。
     */
    @GetMapping("/custom-dicts")
    public Result<List<PlatformCustomDictResponse>> listCustomDicts() {
        return Result.ok(platformCmsService.listCustomDicts());
    }

    /**
     * 创建自定义字典。
     */
    @PostMapping("/custom-dicts")
    public Result<PlatformCustomDictResponse> createCustomDict(@RequestBody @Valid PlatformCustomDictRequest request) {
        return Result.ok(platformCmsService.createCustomDict(request));
    }

    /**
     * 更新自定义字典。
     */
    @PutMapping("/custom-dicts/{dictId}")
    public Result<PlatformCustomDictResponse> updateCustomDict(
        @PathVariable @NotBlank(message = "字典 ID 不能为空") String dictId,
        @RequestBody @Valid PlatformCustomDictRequest request) {
        return Result.ok(platformCmsService.updateCustomDict(dictId, request));
    }

    /**
     * 删除自定义字典。
     */
    @DeleteMapping("/custom-dicts/{dictId}")
    public Result<Void> deleteCustomDict(@PathVariable @NotBlank(message = "字典 ID 不能为空") String dictId) {
        platformCmsService.deleteCustomDict(dictId);
        return Result.ok();
    }

    // ==================== 产品定制 ====================

    /**
     * 查询产品定制列表。
     */
    @GetMapping("/customizeds")
    public Result<List<PlatformCustomizedResponse>> listCustomizeds() {
        return Result.ok(platformCmsService.listCustomizeds());
    }

    /**
     * 创建产品定制。
     */
    @PostMapping("/customizeds")
    public Result<PlatformCustomizedResponse> createCustomized(@RequestBody @Valid PlatformCustomizedRequest request) {
        return Result.ok(platformCmsService.createCustomized(request));
    }

    /**
     * 更新产品定制。
     */
    @PutMapping("/customizeds/{customizedId}")
    public Result<PlatformCustomizedResponse> updateCustomized(
        @PathVariable @NotBlank(message = "定制 ID 不能为空") String customizedId,
        @RequestBody @Valid PlatformCustomizedRequest request) {
        return Result.ok(platformCmsService.updateCustomized(customizedId, request));
    }

    /**
     * 删除产品定制。
     */
    @DeleteMapping("/customizeds/{customizedId}")
    public Result<Void> deleteCustomized(@PathVariable @NotBlank(message = "定制 ID 不能为空") String customizedId) {
        platformCmsService.deleteCustomized(customizedId);
        return Result.ok();
    }
}
