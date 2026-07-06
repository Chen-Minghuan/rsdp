package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.SchemeCandidateBatchCreateRequest;
import com.rsdp.dto.request.SchemeCandidateCreateRequest;
import com.rsdp.dto.request.SchemeCandidateUpdateRequest;
import com.rsdp.dto.response.SchemeCandidateResponse;
import com.rsdp.service.SchemeCandidateService;
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
 * AI 推荐候选清单接口。
 */
@RestController
@RequestMapping("/api/v1/scheme-candidates")
@RequiredArgsConstructor
@Validated
public class SchemeCandidateController {

    private final SchemeCandidateService schemeCandidateService;

    /**
     * 根据推荐请求 ID 查询候选清单。
     *
     * @param recommendRequestId 推荐请求 ID
     * @return 候选清单
     */
    @GetMapping
    public Result<List<SchemeCandidateResponse>> listByRequestId(
        @RequestParam @NotBlank(message = "推荐请求 ID 不能为空") String recommendRequestId) {
        return Result.ok(schemeCandidateService.listByRequestId(recommendRequestId));
    }

    /**
     * 查询当前登录用户的候选清单。
     *
     * @return 候选清单
     */
    @GetMapping("/mine")
    public Result<List<SchemeCandidateResponse>> listMyCandidates() {
        return Result.ok(schemeCandidateService.listMyCandidates());
    }

    /**
     * 查询候选详情。
     *
     * @param candidateId 候选 ID
     * @return 候选详情
     */
    @GetMapping("/{candidateId}")
    public Result<SchemeCandidateResponse> get(
        @PathVariable @NotBlank(message = "候选 ID 不能为空") String candidateId) {
        return Result.ok(schemeCandidateService.getById(candidateId));
    }

    /**
     * 创建单个候选。
     *
     * @param request 创建请求
     * @return 创建后的候选
     */
    @PostMapping
    public Result<SchemeCandidateResponse> create(
        @RequestBody @Valid SchemeCandidateCreateRequest request) {
        return Result.ok(schemeCandidateService.create(request));
    }

    /**
     * 批量创建候选。
     *
     * @param request 批量创建请求
     * @return 创建后的候选列表
     */
    @PostMapping("/batch")
    public Result<List<SchemeCandidateResponse>> batchCreate(
        @RequestBody @Valid SchemeCandidateBatchCreateRequest request) {
        return Result.ok(schemeCandidateService.batchCreate(
            request.getRecommendRequestId(), request.getCandidates()));
    }

    /**
     * 更新候选。
     *
     * @param candidateId 候选 ID
     * @param request     更新请求
     * @return 更新后的候选
     */
    @PutMapping("/{candidateId}")
    public Result<SchemeCandidateResponse> update(
        @PathVariable @NotBlank(message = "候选 ID 不能为空") String candidateId,
        @RequestBody @Valid SchemeCandidateUpdateRequest request) {
        return Result.ok(schemeCandidateService.update(candidateId, request));
    }

    /**
     * 删除候选。
     *
     * @param candidateId 候选 ID
     * @return 空结果
     */
    @DeleteMapping("/{candidateId}")
    public Result<Void> delete(
        @PathVariable @NotBlank(message = "候选 ID 不能为空") String candidateId) {
        schemeCandidateService.delete(candidateId);
        return Result.ok();
    }
}
