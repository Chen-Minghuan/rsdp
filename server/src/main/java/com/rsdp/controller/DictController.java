package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.DictCreateRequest;
import com.rsdp.dto.request.DictStatusRequest;
import com.rsdp.dto.request.DictUpdateRequest;
import com.rsdp.dto.response.DictItemResponse;
import com.rsdp.dto.response.DictTypeSummaryResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.service.DictService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
     * 字典类型汇总（字典管理中心左栏数据源）。
     *
     * @return 各字典类型及条目数
     */
    @GetMapping
    public Result<List<DictTypeSummaryResponse>> listTypeSummary() {
        return Result.ok(dictService.listTypeSummary());
    }

    /**
     * 按类型查询字典项。
     *
     * @param dictType 字典类型，如 style、scene、room_type
     * @param all      true 时返回含停用项的全部条目（字典管理中心使用）；默认仅启用项
     * @return 字典列表
     */
    @GetMapping("/{dictType}")
    public Result<List<DictItemResponse>> listByType(
        @PathVariable @NotBlank(message = "字典类型不能为空") String dictType,
        @RequestParam(required = false, defaultValue = "false") boolean all) {
        List<CategoryDict> dicts = all ? dictService.listAllByType(dictType) : dictService.listByType(dictType);
        return Result.ok(dicts.stream().map(this::toResponse).collect(Collectors.toList()));
    }

    /**
     * 创建字典项。
     *
     * <p>仅允许扩展业务标签类字典（材质/面料/风格/场景等），
     * 业务状态枚举由数据脚本维护。</p>
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
        dict.setParentCode(request.getParentCode());
        dictService.createDict(dict);
        return Result.ok(toResponse(dict));
    }

    /**
     * 更新字典项（名称/英文名/别名/排序），编码与类型不可改。
     *
     * @param dictType 字典类型
     * @param dictCode 字典编码
     * @param request  更新请求
     * @return 更新后的字典项
     */
    @PutMapping("/{dictType}/{dictCode}")
    public Result<DictItemResponse> updateDict(
        @PathVariable @NotBlank(message = "字典类型不能为空") String dictType,
        @PathVariable @NotBlank(message = "字典编码不能为空") String dictCode,
        @Valid @RequestBody DictUpdateRequest request) {
        CategoryDict dict = dictService.updateDict(dictType, dictCode, request.getDictName(),
            request.getDictNameEn(), request.getAliases(), request.getSortOrder());
        return Result.ok(toResponse(dict));
    }

    /**
     * 启停用字典项。停用后不再进入 AI 枚举注入与前端下拉，历史数据展示不受影响。
     *
     * @param dictType 字典类型
     * @param dictCode 字典编码
     * @param request  状态请求
     * @return 更新后的字典项
     */
    @PatchMapping("/{dictType}/{dictCode}/status")
    public Result<DictItemResponse> updateDictStatus(
        @PathVariable @NotBlank(message = "字典类型不能为空") String dictType,
        @PathVariable @NotBlank(message = "字典编码不能为空") String dictCode,
        @Valid @RequestBody DictStatusRequest request) {
        CategoryDict dict = dictService.updateDictStatus(dictType, dictCode, request.getStatus());
        return Result.ok(toResponse(dict));
    }

    private DictItemResponse toResponse(CategoryDict dict) {
        DictItemResponse response = new DictItemResponse();
        response.setDictCode(dict.getDictCode());
        response.setDictName(dict.getDictName());
        response.setDictNameEn(dict.getDictNameEn());
        response.setParentCode(dict.getParentCode());
        response.setSortOrder(dict.getSortOrder());
        response.setStatus(dict.getStatus());
        response.setAliases(dictService.parseAliases(dict.getAliases()));
        return response;
    }
}
