package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.ProductCollectionCreateRequest;
import com.rsdp.dto.request.ProductCollectionUpdateRequest;
import com.rsdp.dto.response.ProductCollectionResponse;
import com.rsdp.service.ProductCollectionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 产品集接口。
 */
@RestController
@RequestMapping("/api/v1/collections")
@RequiredArgsConstructor
@Validated
public class ProductCollectionController {

    private final ProductCollectionService productCollectionService;

    /**
     * 查询产品集列表。
     *
     * @param status 状态筛选（可选）
     * @return 产品集列表
     */
    @GetMapping
    public Result<List<ProductCollectionResponse>> list(
        @RequestParam(required = false) String status) {
        return Result.ok(productCollectionService.list(status));
    }

    /**
     * 查询产品集详情。
     *
     * @param collectionId 产品集 ID
     * @return 产品集详情
     */
    @GetMapping("/{collectionId}")
    public Result<ProductCollectionResponse> get(
        @PathVariable @NotBlank(message = "产品集 ID 不能为空") String collectionId) {
        return Result.ok(productCollectionService.getDetail(collectionId));
    }

    /**
     * 创建产品集。
     *
     * @param request 创建请求
     * @return 创建后的产品集
     */
    @PostMapping
    public Result<ProductCollectionResponse> create(
        @RequestBody @Valid ProductCollectionCreateRequest request) {
        return Result.ok(productCollectionService.create(request));
    }

    /**
     * 更新产品集。
     *
     * @param collectionId 产品集 ID
     * @param request      更新请求
     * @return 更新后的产品集
     */
    @PutMapping("/{collectionId}")
    public Result<ProductCollectionResponse> update(
        @PathVariable @NotBlank(message = "产品集 ID 不能为空") String collectionId,
        @RequestBody @Valid ProductCollectionUpdateRequest request) {
        return Result.ok(productCollectionService.update(collectionId, request));
    }

    /**
     * 删除产品集。
     *
     * @param collectionId 产品集 ID
     * @return 空结果
     */
    @DeleteMapping("/{collectionId}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "产品集 ID 不能为空") String collectionId) {
        productCollectionService.delete(collectionId);
        return Result.ok();
    }
}
