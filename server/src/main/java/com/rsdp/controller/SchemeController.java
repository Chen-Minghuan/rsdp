package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.CopyFromTemplateRequest;
import com.rsdp.dto.request.SaveCanvasLayoutRequest;
import com.rsdp.dto.request.SchemeCreateRequest;
import com.rsdp.dto.request.SchemeItemReorderRequest;
import com.rsdp.dto.request.SchemeQuoteRequest;
import com.rsdp.dto.request.SchemeShareRequest;
import com.rsdp.dto.request.SchemeTemplateRequest;
import com.rsdp.dto.request.SchemeUpdateRequest;
import com.rsdp.dto.response.CopyFromTemplateResponse;
import com.rsdp.dto.response.QuoteResponse;
import com.rsdp.dto.response.SchemeResponse;
import com.rsdp.dto.response.SchemeSummaryResponse;
import com.rsdp.security.SecurityOperatorContext;
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
     * 方案明细拖拽排序（itemIds 为全部明细按新顺序的完整列表；
     * 可选 spaceTags 同事务更新明细空间覆盖标签，键不出现则不动该列）。
     *
     * @param schemeId 方案 ID
     * @param request  排序请求
     * @return 更新后的方案详情
     */
    @PutMapping("/{schemeId}/items/reorder")
    public Result<SchemeResponse> reorderItems(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
        @Valid @RequestBody SchemeItemReorderRequest request) {
        return Result.ok(schemeService.reorderItems(schemeId, request));
    }

    /**
     * 保存方案画布布局（搭配画布）：layout 为明细 ID 字符串 → 位置/缩放/层级的映射，
     * 传 null 或空对象表示清空画布布局。
     *
     * @param schemeId 方案 ID
     * @param request  画布布局请求
     * @return 更新后的方案详情
     */
    @PutMapping("/{schemeId}/canvas-layout")
    public Result<SchemeResponse> saveCanvasLayout(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
        @Valid @RequestBody SaveCanvasLayoutRequest request) {
        return Result.ok(schemeService.saveCanvasLayout(
            schemeId, request.getLayout(), SecurityOperatorContext.currentUsername()));
    }

    /**
     * 设置方案分享开关（V42，免登录公开链接 + 有效期限制；仅方案创建人或 ADMIN）。
     *
     * @param schemeId 方案 ID
     * @param request  分享请求（开关 + 有效期天数，空=永久）
     * @return 更新后的方案详情
     */
    @PutMapping("/{schemeId}/share")
    public Result<SchemeResponse> updateShare(
        @PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
        @Valid @RequestBody SchemeShareRequest request) {
        return Result.ok(schemeService.updateSchemeShare(schemeId, request));
    }

    /**
     * 分页查询回收站中的方案（已软删除）。
     *
     * @param page 页码（从 1 开始）
     * @param size 每页条数（上限 100）
     * @return 已软删方案摘要分页结果
     */
    @GetMapping("/recycle-bin")
    public Result<PageResult<SchemeSummaryResponse>> recycleBin(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "10") @Min(value = 1, message = "每页数量不能小于 1")
        @Max(value = 100, message = "每页数量不能超过 100") long size) {
        return Result.ok(schemeService.listDeletedSchemes(page, size));
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
     * 彻底删除回收站中的方案（物理删除，不可恢复；权限与软删除一致：方案创建人或 ADMIN）。
     *
     * <p>被订单引用的方案属于业务凭证，拒绝删除。</p>
     *
     * @param schemeId 方案 ID
     * @return 空结果
     */
    @DeleteMapping("/{schemeId}/purge")
    public Result<Void> purge(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId) {
        schemeService.purgeScheme(schemeId, SecurityOperatorContext.currentUsername());
        return Result.ok();
    }

    /**
     * 根据方案生成报价单。
     *
     * @param schemeId 方案 ID
     * @param request  请求（可空；mode=cost|sale 报价口径，默认成本核价）
     * @return 报价单
     */
    @PostMapping("/{schemeId}/quote")
    public Result<QuoteResponse> generateQuote(@PathVariable @NotBlank(message = "方案 ID 不能为空") String schemeId,
                                               @RequestBody(required = false) SchemeQuoteRequest request) {
        return Result.ok(schemeService.generateQuote(schemeId, request != null ? request.getMode() : null));
    }
}
