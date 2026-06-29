package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.service.SchemeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 搭配方案接口。
 */
@RestController
@RequestMapping("/api/v1/schemes")
@RequiredArgsConstructor
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
     * 查询方案列表。
     *
     * @return 方案列表
     */
    @GetMapping
    public Result<List<SchemeSummaryResponse>> list() {
        return Result.ok(schemeService.listSchemes());
    }

    /**
     * 查询方案详情。
     *
     * @param schemeId 方案 ID
     * @return 方案详情
     */
    @GetMapping("/{schemeId}")
    public Result<SchemeResponse> detail(@PathVariable String schemeId) {
        return Result.ok(schemeService.getSchemeDetail(schemeId));
    }

    /**
     * 删除搭配方案。
     *
     * @param schemeId 方案 ID
     * @return 空结果
     */
    @DeleteMapping("/{schemeId}")
    public Result<Void> delete(@PathVariable String schemeId) {
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
    public Result<QuoteResponse> generateQuote(@PathVariable String schemeId) {
        return Result.ok(schemeService.generateQuote(schemeId));
    }
}
