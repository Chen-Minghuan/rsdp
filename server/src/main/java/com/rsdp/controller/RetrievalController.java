package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.SimilarProductRequest;
import com.rsdp.dto.response.SimilarProductResponse;
import com.rsdp.service.RetrievalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 视觉/语义检索接口。
 */
@RestController
@RequestMapping("/api/v1/retrieval")
@RequiredArgsConstructor
@Tag(name = "产品检索", description = "以图搜图、以文搜图")
public class RetrievalController {

    private final RetrievalService retrievalService;

    /**
     * 以图搜图 / 以文搜图。
     *
     * @param request 检索请求（支持 image 或 text）
     * @return 相似产品列表
     */
    @PostMapping("/similar")
    @Operation(summary = "相似产品检索", description = "上传图片或输入文本，检索相似产品")
    public Result<List<SimilarProductResponse>> searchSimilar(@ModelAttribute SimilarProductRequest request) {
        List<SimilarProductResponse> results = retrievalService.searchSimilar(request);
        return Result.ok(results);
    }
}
