package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.response.PublicCollectionDetailResponse;
import com.rsdp.dto.response.PublicCollectionSummaryResponse;
import com.rsdp.service.PublicCollectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 用户端官网公开产品集接口（免登录，/api/v1/public/** 由 SecurityConfig 放行）。
 *
 * <p>红线：仅返回已发布（is_published=true）集合；产品项绝不含工厂报价/工厂信息。</p>
 */
@RestController
@RequestMapping("/api/v1/public/collections")
@RequiredArgsConstructor
@Validated
public class PublicCollectionController {

    private final PublicCollectionService publicCollectionService;

    /**
     * 已发布产品集列表。
     *
     * @return 已发布集合列表（含封面图与在售产品数）
     */
    @GetMapping
    public Result<List<PublicCollectionSummaryResponse>> listPublished() {
        return Result.ok(publicCollectionService.listPublished());
    }

    /**
     * 已发布产品集详情（含在售产品项；未发布/不存在返回 404）。
     *
     * @param collectionId 产品集 ID
     * @return 集合详情
     */
    @GetMapping("/{collectionId}")
    public Result<PublicCollectionDetailResponse> detail(@PathVariable String collectionId) {
        return Result.ok(publicCollectionService.getPublishedDetail(collectionId));
    }
}
