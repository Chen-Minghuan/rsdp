package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.response.PublicCategoryResponse;
import com.rsdp.dto.response.PublicProductItemResponse;
import com.rsdp.dto.response.PublicSceneResponse;
import com.rsdp.service.PublicCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

/**
 * 用户端官网公开目录接口（免登录，/api/v1/public/** 由 SecurityConfig 放行）。
 *
 * <p>红线：响应绝不包含 RSKU 工厂报价字段。</p>
 */
@RestController
@RequestMapping("/api/v1/public")
@RequiredArgsConstructor
@Validated
public class PublicCatalogController {

    private final PublicCatalogService publicCatalogService;

    /**
     * 公开商品分页列表（仅在售产品）。
     *
     * @param page      页码（默认 1）
     * @param size      每页条数（默认 12，上限 50）
     * @param category  品类码筛选
     * @param seatCount 座位数筛选
     * @param color     颜色筛选（主色中文名模糊）
     * @param material  材质标签筛选
     * @param priceMin  零售参考价下限
     * @param priceMax  零售参考价上限
     * @param sort      排序：newest（默认）/ price_asc / price_desc
     * @return 分页商品列表
     */
    @GetMapping("/products")
    public Result<PageResult<PublicProductItemResponse>> products(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "12") int size,
        @RequestParam(required = false) String category,
        @RequestParam(required = false) String seatCount,
        @RequestParam(required = false) String color,
        @RequestParam(required = false) String material,
        @RequestParam(required = false) BigDecimal priceMin,
        @RequestParam(required = false) BigDecimal priceMax,
        @RequestParam(required = false) String sort) {
        return Result.ok(publicCatalogService.listProducts(
            page, size, category, seatCount, color, material, priceMin, priceMax, sort));
    }

    /**
     * 空间入口列表（场景字典 + 每个空间一张代表图）。
     *
     * @return 空间入口列表
     */
    @GetMapping("/scenes")
    public Result<List<PublicSceneResponse>> scenes() {
        return Result.ok(publicCatalogService.listScenes());
    }

    /**
     * 类目两级树（category_dict 按 parent_code 组装）。
     *
     * @return 类目树根节点列表
     */
    @GetMapping("/categories")
    public Result<List<PublicCategoryResponse>> categories() {
        return Result.ok(publicCatalogService.listCategories());
    }
}
