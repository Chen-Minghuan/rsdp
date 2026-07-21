package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.CompanyOwnerRequest;
import com.rsdp.dto.request.CompanyRequest;
import com.rsdp.dto.request.JoinCompanyRequest;
import com.rsdp.dto.request.MemberGroupAssignRequest;
import com.rsdp.dto.request.MemberGroupRequest;
import com.rsdp.dto.response.CompanyMemberResponse;
import com.rsdp.dto.response.CompanyResponse;
import com.rsdp.dto.response.InviteRecordResponse;
import com.rsdp.dto.response.MemberGroupResponse;
import com.rsdp.dto.response.MemberSearchResponse;
import com.rsdp.service.CompanyService;
import com.rsdp.service.InviteService;
import com.rsdp.service.MemberGroupService;
import com.rsdp.service.MemberService;
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
 * 用户中心-企业团队接口：企业信息、企业分组、成员管理、认证设计师。
 *
 * <p>所有端点仅需登录；写操作由 Service 层按企业管理员/平台 ADMIN 校验。</p>
 */
@RestController
@RequestMapping("/api/v1/member")
@RequiredArgsConstructor
@Validated
public class MemberController {

    private final CompanyService companyService;
    private final MemberGroupService memberGroupService;
    private final MemberService memberService;
    private final InviteService inviteService;

    /**
     * 查询当前用户的邀请记录（邀请裂变）。
     *
     * @return 邀请记录列表
     */
    @GetMapping("/invites")
    public Result<List<InviteRecordResponse>> listMyInvites() {
        return Result.ok(inviteService.listMyInvites());
    }

    /**
     * 认证设计师：当前用户一键升级（挂标记，VIEWER/USER 补 DESIGNER 角色）。
     *
     * @return 空结果
     */
    @PutMapping("/certified-designer")
    public Result<Void> certifiedDesigner() {
        memberService.certifiedDesigner();
        return Result.ok();
    }

    /**
     * 查询当前用户企业的成员列表。
     *
     * @param groupId 按分组过滤（可选）
     * @return 成员列表
     */
    @GetMapping("/members")
    public Result<List<CompanyMemberResponse>> listMembers(@RequestParam(required = false) String groupId) {
        return Result.ok(memberService.listMembers(groupId));
    }

    /**
     * 搜索可邀请用户（按用户名/昵称，仅未归属企业的启用账号）。
     *
     * @param keyword 关键词
     * @return 候选用户列表
     */
    @GetMapping("/members/search")
    public Result<List<MemberSearchResponse>> searchUsers(
        @RequestParam @NotBlank(message = "搜索关键词不能为空") String keyword) {
        return Result.ok(memberService.searchUsers(keyword));
    }

    /**
     * 邀请用户加入企业。
     *
     * @param request 加入请求
     * @return 加入后的成员信息
     */
    @PostMapping("/members")
    public Result<CompanyMemberResponse> joinCompany(@RequestBody @Valid JoinCompanyRequest request) {
        return Result.ok(memberService.joinCompany(request));
    }

    /**
     * 移出企业成员。
     *
     * @param userId 成员用户 ID
     * @return 空结果
     */
    @DeleteMapping("/members/{userId}")
    public Result<Void> removeMember(
        @PathVariable @NotBlank(message = "用户 ID 不能为空") String userId) {
        memberService.removeMember(userId);
        return Result.ok();
    }

    /**
     * 调整成员分组（groupId 为空表示移出分组）。
     *
     * @param userId  成员用户 ID
     * @param request 分组请求
     * @return 更新后的成员信息
     */
    @PutMapping("/members/{userId}/group")
    public Result<CompanyMemberResponse> updateMemberGroup(
        @PathVariable @NotBlank(message = "用户 ID 不能为空") String userId,
        @RequestBody @Valid MemberGroupAssignRequest request) {
        return Result.ok(memberService.updateMemberGroup(userId, request));
    }

    /**
     * 查询当前用户的企业；无企业时 data 为 null。
     *
     * @return 企业信息
     */
    @GetMapping("/company")
    public Result<CompanyResponse> getMyCompany() {
        return Result.ok(companyService.getMyCompany());
    }

    /**
     * 创建企业（当前用户成为管理员）。
     *
     * @param request 创建请求
     * @return 创建后的企业
     */
    @PostMapping("/company")
    public Result<CompanyResponse> createMyCompany(@RequestBody @Valid CompanyRequest request) {
        return Result.ok(companyService.createMyCompany(request));
    }

    /**
     * 更新当前用户的企业（名称/Logo/折扣率）。
     *
     * @param request 更新请求
     * @return 更新后的企业
     */
    @PutMapping("/company")
    public Result<CompanyResponse> updateMyCompany(@RequestBody @Valid CompanyRequest request) {
        return Result.ok(companyService.updateMyCompany(request));
    }

    /**
     * 变更企业管理员。
     *
     * @param request 变更请求
     * @return 更新后的企业
     */
    @PutMapping("/company/owner")
    public Result<CompanyResponse> transferOwner(@RequestBody @Valid CompanyOwnerRequest request) {
        return Result.ok(companyService.transferOwner(request));
    }

    /**
     * 软删除当前用户的企业（成员归属清空，分组一并软删）。
     *
     * @return 空结果
     */
    @DeleteMapping("/company")
    public Result<Void> deleteMyCompany() {
        companyService.deleteMyCompany();
        return Result.ok();
    }

    /**
     * 查询当前用户企业的分组列表。
     *
     * @return 分组列表
     */
    @GetMapping("/groups")
    public Result<List<MemberGroupResponse>> listMyGroups() {
        return Result.ok(memberGroupService.listMyGroups());
    }

    /**
     * 创建分组。
     *
     * @param request 创建请求
     * @return 创建后的分组
     */
    @PostMapping("/groups")
    public Result<MemberGroupResponse> createGroup(@RequestBody @Valid MemberGroupRequest request) {
        return Result.ok(memberGroupService.createGroup(request));
    }

    /**
     * 更新分组（名称/启停）。
     *
     * @param groupId 分组 ID
     * @param request 更新请求
     * @return 更新后的分组
     */
    @PutMapping("/groups/{groupId}")
    public Result<MemberGroupResponse> updateGroup(
        @PathVariable @NotBlank(message = "分组 ID 不能为空") String groupId,
        @RequestBody @Valid MemberGroupRequest request) {
        return Result.ok(memberGroupService.updateGroup(groupId, request));
    }

    /**
     * 软删除分组（成员 group_id 置空）。
     *
     * @param groupId 分组 ID
     * @return 空结果
     */
    @DeleteMapping("/groups/{groupId}")
    public Result<Void> deleteGroup(
        @PathVariable @NotBlank(message = "分组 ID 不能为空") String groupId) {
        memberGroupService.deleteGroup(groupId);
        return Result.ok();
    }
}
