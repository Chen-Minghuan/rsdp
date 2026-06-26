package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.DictItemResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.service.DictService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
public class DictController {

    private final DictService dictService;

    /**
     * 按类型查询字典项。
     *
     * @param dictType 字典类型，如 style、scene、room_type
     * @return 字典列表
     */
    @GetMapping("/{dictType}")
    public Result<List<DictItemResponse>> listByType(@PathVariable String dictType) {
        List<CategoryDict> dicts = dictService.listByType(dictType);
        return Result.ok(dicts.stream().map(this::toResponse).collect(Collectors.toList()));
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
