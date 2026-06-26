package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.ProductListRequest;
import com.rsdp.dto.request.ProductReviewRequest;
import com.rsdp.dto.response.ProductDetailResponse;
import com.rsdp.dto.response.ProductSummaryResponse;
import com.rsdp.service.ProductQueryService;
import com.rsdp.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import java.util.Map;

/**
 * 产品相关接口。
 */
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final ProductQueryService productQueryService;

    /**
     * 新品录入。
     *
     * @param image 产品图片
     * @return 任务信息
     * @throws IOException 文件保存失败
     */
    @PostMapping("/entry")
    public Result<Map<String, Object>> entry(@RequestParam("image") MultipartFile image) throws IOException {
        Map<String, Object> result = productService.createEntry(image);
        return Result.ok(result);
    }

    /**
     * 产品列表分页查询。
     *
     * @param request 查询条件
     * @return 分页结果
     */
    @GetMapping
    public Result<PageResult<ProductSummaryResponse>> list(ProductListRequest request) {
        return Result.ok(productQueryService.listProducts(request));
    }

    /**
     * 产品详情。
     *
     * @param rspuId RSPU ID
     * @return 产品详情
     */
    @GetMapping("/{rspuId}")
    public Result<ProductDetailResponse> detail(@PathVariable String rspuId) {
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
    public Result<Void> review(@PathVariable String rspuId,
                               @Valid @RequestBody ProductReviewRequest request) {
        productQueryService.reviewProduct(rspuId, request.getReviewStatus(), request.getReviewComment());
        return Result.ok();
    }
}
