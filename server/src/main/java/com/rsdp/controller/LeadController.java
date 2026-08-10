package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.LeadAssignRequest;
import com.rsdp.dto.request.LeadFollowLogRequest;
import com.rsdp.dto.request.LeadStatusRequest;
import com.rsdp.dto.response.LeadAssigneeResponse;
import com.rsdp.dto.response.LeadListItemResponse;
import com.rsdp.dto.response.LeadSourceStatsResponse;
import com.rsdp.service.PlatformLeadService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
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
 * 管理端留资线索接口（需认证，SecurityConfig 限定 ADMIN/EDITOR 角色）。
 */
@RestController
@RequestMapping("/api/v1/leads")
@RequiredArgsConstructor
@Validated
public class LeadController {

    private final PlatformLeadService platformLeadService;

    /**
     * 线索分页列表（手机号脱敏）。
     *
     * @param status 状态筛选（可选）
     * @param source 来源筛选（可选）
     * @param page   页码（默认 1）
     * @param size   每页条数（默认 10，上限 100）
     * @return 分页线索列表
     */
    @GetMapping
    public Result<PageResult<LeadListItemResponse>> listLeads(
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String source,
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "10") int size) {
        return Result.ok(platformLeadService.listLeads(status, source, page, size));
    }

    /**
     * 来源分布统计（含待跟进数，导航角标同用）。
     *
     * @return 来源分布
     */
    @GetMapping("/source-stats")
    public Result<LeadSourceStatsResponse> sourceStats() {
        return Result.ok(platformLeadService.sourceStats());
    }

    /**
     * 跟进人候选（平台运营角色用户）。
     *
     * @return 候选人列表
     */
    @GetMapping("/assignees")
    public Result<List<LeadAssigneeResponse>> assignees() {
        return Result.ok(platformLeadService.listAssignees());
    }

    /**
     * 分配跟进人。
     *
     * @param leadId  线索 ID
     * @param request 分配请求
     * @return 更新后的列表项
     */
    @PutMapping("/{leadId}/assign")
    public Result<LeadListItemResponse> assign(
        @PathVariable @NotBlank(message = "线索 ID 不能为空") String leadId,
        @Valid @RequestBody LeadAssignRequest request) {
        return Result.ok(platformLeadService.assign(leadId, request.getAssignee()));
    }

    /**
     * 追加跟进记录。
     *
     * @param leadId  线索 ID
     * @param request 跟进内容
     * @return 更新后的列表项
     */
    @PostMapping("/{leadId}/follow-logs")
    public Result<LeadListItemResponse> appendFollowLog(
        @PathVariable @NotBlank(message = "线索 ID 不能为空") String leadId,
        @Valid @RequestBody LeadFollowLogRequest request) {
        return Result.ok(platformLeadService.appendFollowLog(leadId, request.getContent()));
    }

    /**
     * 状态流转（仅允许向前：pending → contacted → done）。
     *
     * @param leadId  线索 ID
     * @param request 目标状态
     * @return 更新后的列表项
     */
    @PutMapping("/{leadId}/status")
    public Result<LeadListItemResponse> updateStatus(
        @PathVariable @NotBlank(message = "线索 ID 不能为空") String leadId,
        @Valid @RequestBody LeadStatusRequest request) {
        return Result.ok(platformLeadService.updateStatus(leadId, request.getStatus()));
    }
}
