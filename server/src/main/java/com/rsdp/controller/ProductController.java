package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductReviewRequest;
import com.rsdp.dto.request.ProductUpdateRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.service.ProductQueryService;
import com.rsdp.service.ProductService;
import com.rsdp.util.ImageUploadValidator;
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
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 产品相关接口。
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Validated
public class ProductController {

    private static final long MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024;

    private final ProductService productService;
    private final ProductQueryService productQueryService;
    private final ImageUploadValidator imageUploadValidator;

    /**
     * 新品录入，支持为一个 RSPU 上传多张图片。
     *
     * @param images       产品图片列表，第一张为主图
     * @param categoryCode 品类码，如 FS/DT/CB；为空时默认 FS
     * @return 任务信息
     * @throws IOException 文件保存失败
     */
    @PostMapping("/entry")
    public Result<Map<String, Object>> entry(@RequestParam("images") List<MultipartFile> images,
                                             @RequestParam(value = "categoryCode", required = false) String categoryCode) throws IOException {
        validateImages(images);
        Map<String, Object> result = productService.createEntry(images, categoryCode);
        return Result.ok(result);
    }

    private void validateImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty()) {
            throw new IllegalArgumentException("请至少上传一张图片");
        }
        for (MultipartFile image : images) {
            imageUploadValidator.validate(image, MAX_IMAGE_SIZE_BYTES);
        }
    }

    /**
     * 产品列表分页查询。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<ProductSummaryResponse>> list(@Valid ProductListRequest request) {
        return Result.ok(productQueryService.listProducts(request));
    }

    /**
     * 产品详情。
     *
     * @param rspuId RSPU ID
     * @return 产品详情
     */
    @GetMapping("/{rspuId}")
    public Result<ProductDetailResponse> detail(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId) {
        return Result.ok(productQueryService.getProductDetail(rspuId));
    }

    /**
     * 复核确认产品。
     *
     * @param rspuId  RSPU ID
     * @param request 复核请求
     * @return 空结果
     */
    @PutMapping("/{rspuId}/review")
    public Result<Void> review(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                               @Valid @RequestBody ProductReviewRequest request) {
        productQueryService.reviewProduct(rspuId, request.getReviewStatus(), request.getReviewComment());
        return Result.ok();
    }

    /**
     * 更新产品元数据。
     *
     * @param rspuId  RSPU ID
     * @param request 更新请求
     * @return 空结果
     */
    @PutMapping("/{rspuId}")
    public Result<Void> update(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId,
                               @Valid @RequestBody ProductUpdateRequest request) {
        productQueryService.updateProduct(rspuId, request);
        return Result.ok();
    }

    /**
     * 软删除产品。
     *
     * @param rspuId RSPU ID
     * @return 空结果
     */
    @DeleteMapping("/{rspuId}")
    public Result<Void> delete(@PathVariable @NotBlank(message = "RSPU ID 不能为空") String rspuId) {
        productQueryService.deleteProduct(rspuId);
        return Result.ok();
    }
}
