package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.security.Permissions;
import com.rsdp.service.CatalogSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 商品列表参考图检索入口。 */
@RestController
@RequiredArgsConstructor
public class CatalogImageSearchController {

    private final CatalogSearchService catalogSearchService;

    /** 创建管理端参考图搜索。 */
    @PostMapping(value = "/api/v1/products/image-search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('" + Permissions.PRODUCT_READ + "')")
    public Result<String> imageSearch(@RequestParam("image") MultipartFile image) {
        return Result.ok(catalogSearchService.startImageSearch(image, false));
    }

    /** 创建官网参考图搜索，仅召回在售商品。 */
    @PostMapping(value = "/api/v1/public/products/image-search", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<String> publicImageSearch(@RequestParam("image") MultipartFile image) {
        return Result.ok(catalogSearchService.startImageSearch(image, true));
    }
}
