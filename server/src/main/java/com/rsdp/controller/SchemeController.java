package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.CopyFromTemplateRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeTemplateRequest;
import com.rsdp.dto.request.SchemeUpdateRequest;
import com.rsdp.dto.response.CopyFromTemplateResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.service.SchemeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
 * 搭配方案接口。
 */
@RestController
@RequestMapping("/api/v1/schemes")
@RequiredArgsConstructor
@Validated
public class SchemeController {

    private final SchemeService schemeService;

    /**
     * 创建搭配方案。
     *
     * @param request 创建请求
     * @return 方案详情
     */
    @PostMapping
    public Result<SchemeResponse> create(@Valid @RequestBody SchemeCreateRequest request) {
        return Result.ok(schemeService.createScheme(request));
    }

    /**
     * 分页查询方案列表（支持模板与标签筛选）。
     *
     * @param isTemplate 是否仅查模板（可选）
     * @param tag        模板标签筛选（可选）
     * @param page       页码（从 1 开始）
     * @param size       每页条数（上限 100）
     * @return 方案摘要分页结果
     */
    @GetMapping
    public Result<PageResult<SchemeSummaryResponse>> list(
        @RequestParam(required = false) Boolean isTemplate,
        @RequestParam(required = false) String tag,
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页数量不能小于 1")
        @Max(value = 100, message = "每页数量不能超过 100") long size) {
        return Result.ok(schemeService.listSchemes(isTemplate, tag, page, size));
    }

    /**
     * 套用模板创建新方案（价格取 RSKU 当前最新价）。
     *
     * @param schemeId 模板方案 ID
     * @param request  套用请求
     * @return 新方案与价格变动对比
     */
    @PostMapping("/{schemeId}/copy-from-template")
    public Result<CopyFromTemplateResponse> copyFromTemplate(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
        @Valid @RequestBody CopyFromTemplateRequest request) {
        return Result.ok(schemeService.copyFromTemplate(schemeId, request));
    }

    /**
     * 设为/取消方案模板。
     *
     * @param schemeId 方案 ID
     * @param request  模板设置请求
     * @return 更新后的方案详情
     */
    @PutMapping("/{schemeId}/template")
    public Result<SchemeResponse> setTemplate(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
        @Valid @RequestBody SchemeTemplateRequest request) {
        return Result.ok(schemeService.setTemplate(schemeId, request));
    }

    /**
     * 查询方案详情。
     *
     * @param schemeId 方案 ID
     * @return 方案详情
     */
    @GetMapping("/{schemeId}")
    public Result<SchemeResponse> detail(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId) {
        return Result.ok(schemeService.getSchemeDetail(schemeId));
    }

    /**
     * 更新搭配方案。
     *
     * @param schemeId 方案 ID
     * @param request  更新请求
     * @return 更新后的方案详情
     */
    @PutMapping("/{schemeId}")
    public Result<SchemeResponse> update(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
                                         @Valid @RequestBody SchemeUpdateRequest request) {
        return Result.ok(schemeService.updateScheme(schemeId, request));
    }

    /**
     * 删除搭配方案。
     *
     * @param schemeId 方案 ID
     * @return 空结果
     */
    @DeleteMapping("/{schemeId}")
    public Result<Void> delete(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId) {
        schemeService.deleteScheme(schemeId);
        return Result.ok();
    }

    /**
     * 根据方案生成报价单。
     *
     * @param schemeId 方案 ID
     * @return 报价单
     */
    @PostMapping("/{schemeId}/quote")
    public Result<QuoteResponse> generateQuote(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId) {
        return Result.ok(schemeService.generateQuote(schemeId));
    }
}
