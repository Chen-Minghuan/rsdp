package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.TemplateTagRequest;
import com.rsdp.dto.response.TemplateTagResponse;
import com.rsdp.service.TemplateTagService;
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
 * 模板标签接口。
 *
 * <p>{@code simple-list} 登录即可读（模板库页/设模板选择器）；
 * 管理端 CRUD 由 SecurityConfig 限定 ADMIN/EDITOR 角色。</p>
 */
@RestController
@RequestMapping("/api/v1/template-tags")
@RequiredArgsConstructor
@Validated
public class TemplateTagController {

    private final TemplateTagService templateTagService;

    /**
     * 查询启用的模板标签（登录即可读）。
     *
     * @return 启用标签列表
     */
    @GetMapping("/simple-list")
    public Result<List<TemplateTagResponse>> simpleList() {
        return Result.ok(templateTagService.simpleList());
    }

    /**
     * 查询全部模板标签（含停用，管理端）。
     *
     * @return 标签列表
     */
    @GetMapping
    public Result<List<TemplateTagResponse>> listAll() {
        return Result.ok(templateTagService.listAll());
    }

    /**
     * 创建模板标签。
     *
     * @param request 创建请求
     * @return 创建后的标签
     */
    @PostMapping
    public Result<TemplateTagResponse> create(@RequestBody @Valid TemplateTagRequest request) {
        return Result.ok(templateTagService.create(request));
    }

    /**
     * 更新模板标签（重命名/排序/启停）。
     *
     * @param tagId   标签 ID
     * @param request 更新请求
     * @return 更新后的标签
     */
    @PutMapping("/{tagId}")
    public Result<TemplateTagResponse> update(
        @PathVariable @NotBlank(message = "标签 ID 不能为空") String tagId,
        @RequestBody @Valid TemplateTagRequest request) {
        return Result.ok(templateTagService.update(tagId, request));
    }

    /**
     * 删除模板标签（仍被模板使用时拒绝）。
     *
     * @param tagId 标签 ID
     * @return 空结果
     */
    @DeleteMapping("/{tagId}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "标签 ID 不能为空") String tagId) {
        templateTagService.delete(tagId);
        return Result.ok();
    }
}
