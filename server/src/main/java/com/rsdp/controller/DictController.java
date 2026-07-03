package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.DictCreateRequest;
import com.rsdp.dto.response.DictItemResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.service.DictService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 字典接口。
 */
@RestController
@RequestMapping("/api/v1/dicts")
@RequiredArgsConstructor
@Validated
public class DictController {

    private final DictService dictService;

    /**
     * 按类型查询字典项。
     *
     * @param dictType 字典类型，如 style、scene、room_type
     * @return 字典列表
     */
    @GetMapping("/{dictType}")
    public Result<List<DictItemResponse>> listByType(@PathVariable @NotBlank(message = "字典类型不能为空") String dictType) {
        List<CategoryDict> dicts = dictService.listByType(dictType);
        return Result.ok(dicts.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    /**
     * 创建字典项。
     *
     * <p>当前仅支持用户扩展 {@code material} 与 {@code scene} 两类字典，
     * 用于在前端产品编辑表单中快速新增标签。</p>
     *
     * @param request 字典项创建请求
     * @return 创建后的字典项
     */
    @PostMapping
    public Result<DictItemResponse> createDict(@Valid @RequestBody DictCreateRequest request) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType(request.getDictType());
        dict.setDictCode(request.getDictCode());
        dict.setDictName(request.getDictName());
        dict.setDictNameEn(request.getDictNameEn());
        dictService.createDict(dict);
        return Result.ok(toResponse(dict));
    }

    private DictItemResponse toResponse(CategoryDict dict) {
        DictItemResponse response = new DictItemResponse();
        response.setDictCode(dict.getDictCode());
        response.setDictName(dict.getDictName());
        response.setDictNameEn(dict.getDictNameEn());
        response.setParentCode(dict.getParentCode());
        response.setSortOrder(dict.getSortOrder());
        return response;
    }
}
