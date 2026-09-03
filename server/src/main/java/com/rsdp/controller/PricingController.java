package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.PricingRuleUpsertRequest;
import com.rsdp.dto.response.PricingPreviewItemResponse;
import com.rsdp.dto.response.PricingPreviewSummaryResponse;
import com.rsdp.dto.response.PricingRuleResponse;
import com.rsdp.service.PricingPreviewService;
import com.rsdp.service.PricingRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 定价管理接口：品类级加价规则维护（pricing:update，仅 ADMIN 持有）与定价试算（product:read）。
 */
@RestController
@RequestMapping("/api/v1/pricing")
@RequiredArgsConstructor
@Validated
public class PricingController {

    private final PricingRuleService pricingRuleService;
    private final PricingPreviewService pricingPreviewService;

    /**
     * 品类加价规则列表（含品类名称）。
     *
     * @return 规则列表
     */
    @GetMapping("/rules")
    public Result<List<PricingRuleResponse>> listRules() {
        return Result.ok(pricingRuleService.listRules());
    }

    /**
     * 按品类编码 upsert 加价规则（倍率必须 &gt; 0）。
     *
     * @param categoryCode 品类编码
     * @param request      请求
     * @return 生效后的规则
     */
    @PutMapping("/rules/{categoryCode}")
    public Result<PricingRuleResponse> upsertRule(@PathVariable @NotBlank(message = "品类编码不能为空") String categoryCode,
                                                  @Valid @RequestBody PricingRuleUpsertRequest request) {
        return Result.ok(pricingRuleService.upsertRule(categoryCode, request));
    }

    /**
     * 按品类编码删除加价规则。
     *
     * @param categoryCode 品类编码
     * @return 空响应
     */
    @DeleteMapping("/rules/{categoryCode}")
    public Result<Void> deleteRule(@PathVariable @NotBlank(message = "品类编码不能为空") String categoryCode) {
        pricingRuleService.deleteRule(categoryCode);
        return Result.ok();
    }

    /**
     * 定价试算清单（分页；基准为在售且成本最低的 RSKU）。
     *
     * @param categoryCode 品类编码筛选（可空）
     * @param source       售价来源筛选（MANUAL/CATEGORY_RULE/GLOBAL/NONE，可空）
     * @param keyword      关键词（可空）
     * @param page         页码
     * @param size         每页条数
     * @return 分页试算清单
     */
    @GetMapping("/preview")
    public Result<PageResult<PricingPreviewItemResponse>> preview(
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") @Min(1) long page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) long size) {
        return Result.ok(pricingPreviewService.preview(categoryCode, source, keyword, page, size));
    }

    /**
     * 定价试算总览计数（全部在售产品按售价来源分组 + 低于成本数）。
     *
     * @return 总览计数
     */
    @GetMapping("/preview/summary")
    public Result<PricingPreviewSummaryResponse> previewSummary() {
        return Result.ok(pricingPreviewService.summary());
    }
}
