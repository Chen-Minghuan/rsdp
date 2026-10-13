package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.FloorPlanActivateRequest;
import com.rsdp.dto.request.FloorPlanBatchDeleteRequest;
import com.rsdp.dto.request.FloorPlanConfirmRequest;
import com.rsdp.dto.request.FloorPlanFurnitureConfirmRequest;
import com.rsdp.dto.request.FloorPlanSchemeRequest;
import com.rsdp.dto.request.FloorPlanTargetSceneRequest;
import com.rsdp.dto.response.FloorPlanFurnitureEditResponse;
import com.rsdp.dto.request.ManualFloorPlanCreateRequest;
import com.rsdp.dto.request.ManualFloorPlanLayoutRequest;
import com.rsdp.dto.response.FloorPlanAnalysisListItemResponse;
import com.rsdp.dto.response.FloorPlanAnalysisResponse;
import com.rsdp.dto.response.FloorPlanBatchDeleteResponse;
import com.rsdp.dto.response.ManualFloorPlanCreateResponse;
import com.rsdp.dto.response.ManualFloorPlanLayoutSaveResponse;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.service.FloorPlanMatchingService;
import com.rsdp.service.FloorPlanService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
     * <p>三种组合（CAD 户型导入增强·图片+CAD 双文件通道）：仅 image → 视觉识别通道；
     * 仅 cad → CAD 解析通道（同 P3 单文件 CAD 行为）；image + cad → 双文件新模式
     * （CAD 出精确数据和规范预览，image 展示时仅作只读参考，并用于补全 CAD 空间名称和类型；
     * 视觉结果不会覆盖 CAD polygon、面积和尺寸）。三者至少其一。</p>
     *
     * @param image      户型图片（jpg/png，≤10MB；双文件模式下作只读参考图及空间语义来源），可选
     * @param cad        CAD 图纸（dwg/dxf，≤20MB），可选
     * @param hint       用户补充说明（可选）
     * @param projectId  归属项目 ID（可选，V14；非空时校验项目存在且当前用户可见）
     * @param sourceName 户型名称/备注（可选，V14，≤128 字）
     * @return analysisId + taskId
     */
    @PostMapping("/analyze")
    public Result<Map<String, String>> analyze(
        @RequestParam(value = "image", required = false) MultipartFile image,
        @RequestParam(value = "cad", required = false) MultipartFile cad,
        @RequestParam(required = false) String hint,
        @RequestParam(required = false) String projectId,
        @RequestParam(required = false) String sourceName) {
        return Result.ok(floorPlanService.analyze(image, cad, hint, projectId, sourceName));
    }

    /**
     * 创建无图纸手画布局草稿，不上传图片、不触发 AI 任务。
     *
     * @param request 画布尺寸、网格、项目与户型名称
     * @return 草稿分析 ID 与初始 revision
     */
    @PostMapping("/manual")
    public Result<ManualFloorPlanCreateResponse> createManualLayout(
        @Valid @RequestBody ManualFloorPlanCreateRequest request) {
        return Result.ok(floorPlanService.createManualLayout(request));
    }

    /**
     * 保存手画布局完整草稿，使用 expectedRevision 防止并发覆盖。
     *
     * @param analysisId 分析批次 ID
     * @param request 完整布局快照
     * @return 保存后的新 revision
     */
    @PutMapping("/{analysisId}/layout")
    public Result<ManualFloorPlanLayoutSaveResponse> saveManualLayout(
        @PathVariable String analysisId,
        @Valid @RequestBody ManualFloorPlanLayoutRequest request) {
        return Result.ok(floorPlanService.saveManualLayout(analysisId, request));
    }

    /**
     * 确认手画布局并物化空间几何，确认后可直接进入现有方案生成链路。
     *
     * @param analysisId 分析批次 ID
     * @return 已确认分析详情
     */
    @PostMapping("/{analysisId}/layout/confirm")
    public Result<FloorPlanAnalysisResponse> confirmManualLayout(@PathVariable String analysisId) {
        return Result.ok(floorPlanService.confirmManualLayout(analysisId));
    }

    /**
     * 分析历史列表（P1）：分页 + 可选 status / projectId（V14）过滤，按创建时间倒序。
     * 归属隔离与详情同口径（平台运营全见，其他角色仅本人创建）。
     *
     * @param page      页码（从 1 开始）
     * @param size      每页条数（1~100）
     * @param status    状态过滤（可选）
     * @param projectId 归属项目过滤（可选）
     * @param unassigned 是否只查询未归属项目的记录（默认 false；为 true 时忽略 projectId）
     * @return 分页列表（含 roomCount 批量统计、缩略图、项目名称、几何来源与质量提示数）
     */
    @GetMapping
    public Result<PageResult<FloorPlanAnalysisListItemResponse>> listAnalyses(
        @RequestParam(defaultValue = "1") long page,
        @RequestParam(defaultValue = "20") long size,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String projectId,
        @RequestParam(defaultValue = "false") boolean unassigned) {
        return Result.ok(floorPlanService.listAnalyses(page, size, status, projectId, unassigned));
    }

    /**
     * 接口 2：查询分析状态与空间列表（前端轮询入口，以 task 状态同步校正 analysis 状态）。
     *
     * <p>V20 Revision 化：默认装配 revision = working ?? active ?? latest；
     * 指定 analysisRevision 时校验其属于本分析（404）。响应含 Revision 元数据、
     * 家具列表与激活就绪度。</p>
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 指定 Scene Revision 序号（可选）
     * @return 分析详情（含空间/家具列表）
     */
    @GetMapping("/{analysisId}")
    public Result<FloorPlanAnalysisResponse> getAnalysis(
        @PathVariable String analysisId,
        @RequestParam(required = false) Integer analysisRevision) {
        return Result.ok(floorPlanService.getAnalysis(analysisId, analysisRevision));
    }

    /**
     * 接口 3：人工校正，整体提交确认后的空间列表（增删改尺寸），状态 → confirmed。
     *
     * <p>V20 Revision 化：请求携带 analysisRevision + baseRoomRevision 时走 Revision-aware
     * 编辑（只改工作 Revision 的 Room 对象，不改 active 指针、不置 confirmed）；
     * 不携带时为 legacy 兼容路径（仅 latest=1 允许，内部绑定 Revision 1 并隐式激活）。</p>
     *
     * @param analysisId 分析批次 ID
     * @param request    校正后的空间列表 + 可选比例尺/Revision 版本
     * @return 校正后的分析详情
     */
    @PutMapping("/{analysisId}/rooms")
    public Result<FloorPlanAnalysisResponse> confirmRooms(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanConfirmRequest request) {
        return Result.ok(floorPlanService.confirmRooms(analysisId, request));
    }

    /**
     * 家具人工校正（V20，P0-B）：整体提交该 Analysis Revision 下最终家具列表
     * （已有 ID 更新人工字段、缺席软删、无 ID 新增/拆分/合并），
     * 绑定 analysisRevision + baseFurnitureRevision 双版本；409 语义见 API 设计文档。
     *
     * @param analysisId 分析批次 ID
     * @param request    家具列表 + analysisRevision + baseFurnitureRevision
     * @return {analysisRevision, furnitureRevision, furniture[]}
     */
    @PutMapping("/{analysisId}/furniture")
    public Result<FloorPlanFurnitureEditResponse> confirmFurniture(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanFurnitureConfirmRequest request) {
        return Result.ok(floorPlanService.editRevisionFurniture(analysisId, request));
    }

    /**
     * Scene 原子激活（V20，§8.5）：单事务切换 active_analysis_revision 指针
     * （旧 ACTIVE → SUPERSEDED，目标 → ACTIVE，analysis → confirmed）。
     * 版本/校验失败 409 并整体回滚。
     *
     * @param analysisId       分析批次 ID
     * @param analysisRevision 目标 Scene Revision 序号
     * @param request          {baseActiveAnalysisRevision, expectedRoomRevision, expectedFurnitureRevision}
     * @return 激活后的分析详情
     */
    @PostMapping("/{analysisId}/revisions/{analysisRevision}/activate")
    public Result<FloorPlanAnalysisResponse> activateRevision(
        @PathVariable String analysisId,
        @PathVariable Integer analysisRevision,
        @Valid @RequestBody FloorPlanActivateRequest request) {
        return Result.ok(floorPlanService.activateRevision(analysisId, analysisRevision, request));
    }

    /**
     * 派生工作 Revision（V20，§8.4.6）：从 active（无 active 则 latest）复制已确认对象
     * 与自动快照，生成新的可编辑工作 Revision；已有工作 Revision 时 409。
     *
     * @param analysisId 分析批次 ID
     * @return {analysisId, analysisRevision, revisionStatus}
     */
    @PostMapping("/{analysisId}/working-revision")
    public Result<Map<String, Object>> createWorkingRevision(@PathVariable String analysisId) {
        return Result.ok(floorPlanService.createWorkingRevision(analysisId));
    }

    /**
     * Target Scene 切换（V20，§8.2）：不重新上传 DWG，从 cad_object_key 读原文件重跑
     * Parser，产生新 Analysis Revision（异步，HTTP 202）；旧 Revision 不删不改。
     *
     * @param analysisId 分析批次 ID
     * @param request    {baseAnalysisRevision, targetId}
     * @return 202 {analysisId, analysisRevision, taskId, status=PROCESSING}
     */
    @PutMapping("/{analysisId}/target-scene")
    public ResponseEntity<Result<Map<String, Object>>> switchTargetScene(
        @PathVariable String analysisId,
        @Valid @RequestBody FloorPlanTargetSceneRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(Result.ok(floorPlanService.switchTargetScene(analysisId, request)));
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

    /**
     * 批量软删分析批次。每条记录使用独立事务，单条失败不影响其余记录。
     *
     * @param request 批量删除请求（分析批次 ID 列表，单次最多 100 条）
     * @return 删除结果（成功数 + 失败明细）
     */
    @PostMapping("/batch-delete")
    public Result<FloorPlanBatchDeleteResponse> batchDeleteAnalyses(
        @Valid @RequestBody FloorPlanBatchDeleteRequest request) {
        return Result.ok(floorPlanService.batchDeleteAnalyses(request.getAnalysisIds()));
    }
}
