package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.RspuRelationCreateRequest;
import com.rsdp.dto.request.RspuRelationUpdateRequest;
import com.rsdp.dto.response.RspuRelationResponse;
import com.rsdp.service.RspuRelationService;
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
 * RSPU 产品间关系管理接口。
 */
@RestController
@RequestMapping("/api/v1/products/{rspuId}/relations")
@RequiredArgsConstructor
@Validated
public class RspuRelationController {

    private final RspuRelationService relationService;

    /**
     * 查询某产品作为锚点的所有有效搭配关系。
     *
     * @param rspuId 锚点产品 ID
     * @return 搭配关系列表
     */
    @GetMapping
    public Result<List<RspuRelationResponse>> listRelations(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId) {
        return Result.ok(relationService.listByAnchor(rspuId));
    }

    /**
     * 为某产品创建搭配关系。
     *
     * @param rspuId  锚点产品 ID
     * @param request 创建请求
     * @return 空结果
     */
    @PostMapping
    public Result<Void> createRelation(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                                       @Valid @RequestBody RspuRelationCreateRequest request) {
        relationService.createRelation(rspuId, request);
        return Result.ok();
    }

    /**
     * 更新搭配关系。
     *
     * @param rspuId     锚点产品 ID
     * @param relationId 关系 ID
     * @param request    更新请求
     * @return 空结果
     */
    @PutMapping("/{relationId}")
    public Result<Void> updateRelation(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                                       @PathVariable @NotBlank(message = "关系 ID 不能为空") String relationId,
                                       @Valid @RequestBody RspuRelationUpdateRequest request) {
        relationService.updateRelation(rspuId, relationId, request);
        return Result.ok();
    }

    /**
     * 删除搭配关系。
     *
     * @param rspuId     锚点产品 ID
     * @param relationId 关系 ID
     * @return 空结果
     */
    @DeleteMapping("/{relationId}")
    public Result<Void> deleteRelation(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                                       @PathVariable @NotBlank(message = "关系 ID 不能为空") String relationId) {
        relationService.deleteRelation(rspuId, relationId);
        return Result.ok();
    }
}
