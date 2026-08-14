package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.request.FloorPlanSchemeRequest;
import com.rsdp.dto.response.FloorPlanAnalysisListItemResponse;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.FloorPlanMatchingService;
import com.rsdp.service.FloorPlanService;
import jakarta.validation.Valid;
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

import java.util.Map;

/**
 * 户型图分析管理端接口（方案 v3.0 §4.2）。
 *
 * <p>授权：POST /analyze 需 product:read；查询/校正/删除登录即可，
 * 数据归属校验在 Service 层（平台运营可见全部，其他用户仅本人）。</p>
 */
@RestController
@RequestMapping("/api/v1/floor-plan")
@RequiredArgsConstructor
@Validated
public class FloorPlanController {

    private final FloorPlanService floorPlanService;
    private final FloorPlanMatchingService floorPlanMatchingService;

    /**
     * 接口 1：上传户型图并创建异步分析任务。
     *
     * @param image 户型图片（jpg/png，≤10MB）
     * @param hint  用户补充说明（可选）
     * @return analysisId + taskId
     */
    @PostMapping("/analyze")
    public Result<Map<String, String>> analyze(
        @RequestParam("image") MultipartFile image,
        @RequestParam(required = false) String hint) {
        return Result.ok(floorPlanService.analyze(image, hint));
    }

    /**
     * 分析历史列表（P1）：分页 + 可选 status 过滤，按创建时间倒序。
     * 归属隔离与详情同口径（平台运营全见，其他角色仅本人创建）。
     *
     * @param page   页码（从 1 开始）
     * @param size   每页条数（1~100）
     * @param status 状态过滤（可选）
     * @return 分页列表（含 roomCount 批量统计）
     */
    @GetMapping
    public Result<PageResult<FloorPlanAnalysisListItemResponse>> listAnalyses(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size,
        @RequestParam(required = false) String status) {
        return Result.ok(floorPlanService.listAnalyses(page, size, status));
    }

    /**
     * 接口 2：查询分析状态与空间列表（前端轮询入口，以 task 状态同步校正 analysis 状态）。
     *
     * @param analysisId 分析批次 ID
     * @return 分析详情（含空间列表）
     */
    @GetMapping("/{analysisId}")
    public Result<FloorPlanAnalysisResponse> getAnalysis(@PathVariable String analysisId) {
        return Result.ok(floorPlanService.getAnalysis(analysisId));
    }

    /**
     * 接口 3：人工校正，整体提交确认后的空间列表（增删改尺寸），状态 → confirmed。
     *
     * @param analysisId 分析批次 ID
     * @param request    校正后的空间列表 + 可选比例尺
     * @return 校正后的分析详情
     */
    @PutMapping("/{analysisId}/rooms")
    public Result<FloorPlanAnalysisResponse> confirmRooms(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanConfirmRequest request) {
        return Result.ok(floorPlanService.confirmRooms(analysisId, request));
    }

    /**
     * 接口 4：基于 confirmed 空间生成搭配方案，落 scheme + scheme_item（scheme.analysis_id 回填溯源）。
     *
     * @param analysisId 分析批次 ID
     * @param request    搭配生成请求（roomId + 可选风格/预算/项目）
     * @return schemeId
     */
    @PostMapping("/{analysisId}/scheme")
    public Result<Map<String, String>> generateScheme(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanSchemeRequest request) {
        String schemeId = floorPlanMatchingService.generateSchemeForAnalysis(
            analysisId, request, SecurityOperatorContext.currentUsername());
        return Result.ok(Map.of("schemeId", schemeId));
    }

    /**
     * 失败重试（P1）：仅 failed 状态可重试（其他状态 400 中文提示）；
     * 重置状态为 pending + 清 errorMessage + 新建异步任务重新触发识别。
     *
     * @param analysisId 分析批次 ID
     * @return 新任务 taskId
     */
    @PostMapping("/{analysisId}/retry")
    public Result<Map<String, String>> retry(@PathVariable String analysisId) {
        return Result.ok(floorPlanService.retry(analysisId));
    }

    /**
     * 接口 5：软删分析批次（级联软删空间明细）。
     *
     * @param analysisId 分析批次 ID
     * @return 空结果
     */
    @DeleteMapping("/{analysisId}")
    public Result<Void> deleteAnalysis(@PathVariable String analysisId) {
        floorPlanService.deleteAnalysis(analysisId);
        return Result.ok();
    }
}
