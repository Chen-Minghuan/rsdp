package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.JoinCompanyRequest;
import com.rsdp.dto.request.MemberGroupAssignRequest;
import com.rsdp.dto.response.CompanyMemberResponse;
import com.rsdp.dto.response.MemberSearchResponse;
import com.rsdp.entity.Company;
import com.rsdp.entity.MemberGroup;
import com.rsdp.entity.SysRole;
import com.rsdp.entity.SysUser;
import com.rsdp.entity.SysUserRole;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.MemberGroupMapper;
import com.rsdp.mapper.SysRoleMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.mapper.SysUserRoleMapper;
import com.rsdp.security.SecurityOperatorContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 企业成员服务：成员列表、搜索邀请、移出、调分组、认证设计师升级。
 *
 * <p>成员写操作仅企业管理员（owner）或平台 ADMIN 可执行。</p>
 */
@Service
@RequiredArgsConstructor
public class MemberService {

    /** 认证升级目标角色。 */
    public static final String DESIGNER_ROLE = "DESIGNER";

    /** 成员搜索候选上限。 */
    private static final int SEARCH_LIMIT = 20;

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final MemberGroupMapper memberGroupMapper;
    private final CompanyService companyService;
    private final UserRoleService userRoleService;
    private final AuditLogService auditLogService;

    /**
     * 查询当前用户企业的成员列表（企业成员均可查看）。
     *
     * @param groupId 按分组过滤（可选）
     * @return 成员列表
     */
    public List<CompanyMemberResponse> listMembers(String groupId) {
        Company company = companyService.getMyCompanyRequired();
        QueryWrapper<SysUser> wrapper = new QueryWrapper<SysUser>()
            .eq("company_id", company.getCompanyId())
            .orderByAsc("created_at");
        if (StringUtils.hasText(groupId)) {
            wrapper.eq("group_id", groupId);
        }
        List<SysUser> members = sysUserMapper.selectList(wrapper);
        Map<String, String> roleCodeMap = batchFirstRoleCode(
            members.stream().map(SysUser::getUserId).toList());
        return members.stream()
            .map(u -> toMemberResponse(u, company.getOwnerId(), roleCodeMap.get(u.getUserId())))
            .toList();
    }

    /**
     * 搜索可邀请用户：按用户名/昵称匹配，仅返回未归属企业的启用账号（上限 20 条）。
     * 仅企业管理员或平台 ADMIN。
     *
     * @param keyword 关键词
     * @return 候选用户列表
     */
    public List<MemberSearchResponse> searchUsers(String keyword) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);
        if (!StringUtils.hasText(keyword)) {
            throw new BusinessException("搜索关键词不能为空");
        }
        List<SysUser> users = sysUserMapper.selectList(new QueryWrapper<SysUser>()
            .isNull("company_id")
            .eq("status", "active")
            .and(w -> w.like("username", keyword.trim()).or().like("nickname", keyword.trim()))
            .orderByAsc("created_at")
            .last("LIMIT " + SEARCH_LIMIT));
        String currentUserId = SecurityOperatorContext.currentUserId();
        return users.stream()
            .filter(u -> !u.getUserId().equals(currentUserId))
            .map(this::toSearchResponse)
            .toList();
    }

    /**
     * 邀请用户加入企业。仅企业管理员或平台 ADMIN；目标用户须为未归属企业的启用账号。
     *
     * @param request 加入请求（可指定初始分组）
     * @return 加入后的成员信息
     */
    @Transactional
    public CompanyMemberResponse joinCompany(JoinCompanyRequest request) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);

        SysUser target = sysUserMapper.selectById(request.getUserId());
        if (target == null || !"active".equals(target.getStatus())) {
            throw new ResourceNotFoundException("用户不存在或已停用: " + request.getUserId());
        }
        if (StringUtils.hasText(target.getCompanyId())) {
            throw new BusinessException("该用户已归属其他企业");
        }
        MemberGroup group = null;
        if (StringUtils.hasText(request.getGroupId())) {
            group = getEnabledGroupInCompany(request.getGroupId(), company.getCompanyId());
        }

        SysUser oldSnapshot = snapshot(target);
        target.setCompanyId(company.getCompanyId());
        target.setCompanyName(company.getCompanyName());
        target.setGroupId(group != null ? group.getGroupId() : null);
        target.setGroupName(group != null ? group.getGroupName() : null);
        target.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(target);
        auditLogService.logUpdate("sys_user", target.getUserId(), oldSnapshot, snapshot(target), currentUsername());

        String roleCode = userRoleService.getRoleCodesByUserId(target.getUserId()).stream()
            .findFirst().orElse(null);
        return toMemberResponse(target, company.getOwnerId(), roleCode);
    }

    /**
     * 移出企业成员。仅企业管理员或平台 ADMIN；企业管理员需先变更管理员才能移出。
     *
     * @param userId 成员用户 ID
     */
    @Transactional
    public void removeMember(String userId) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);

        SysUser target = getMemberInCompany(userId, company);
        if (company.getOwnerId().equals(userId)) {
            throw new BusinessException("请先变更企业管理员，再移出该成员");
        }

        SysUser oldSnapshot = snapshot(target);
        target.setCompanyId(null);
        target.setCompanyName(null);
        target.setGroupId(null);
        target.setGroupName(null);
        target.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.update(null, new UpdateWrapper<SysUser>()
            .eq("user_id", userId)
            .set("company_id", null)
            .set("company_name", null)
            .set("group_id", null)
            .set("group_name", null)
            .set("updated_at", target.getUpdatedAt()));
        auditLogService.logUpdate("sys_user", userId, oldSnapshot, snapshot(target), currentUsername());
    }

    /**
     * 调整成员分组（groupId 为空表示移出分组）。仅企业管理员或平台 ADMIN。
     *
     * @param userId  成员用户 ID
     * @param request 分组请求
     * @return 更新后的成员信息
     */
    @Transactional
    public CompanyMemberResponse updateMemberGroup(String userId, MemberGroupAssignRequest request) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);

        SysUser target = getMemberInCompany(userId, company);
        MemberGroup group = null;
        if (StringUtils.hasText(request.getGroupId())) {
            group = getEnabledGroupInCompany(request.getGroupId(), company.getCompanyId());
        }

        SysUser oldSnapshot = snapshot(target);
        target.setGroupId(group != null ? group.getGroupId() : null);
        target.setGroupName(group != null ? group.getGroupName() : null);
        target.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.update(null, new UpdateWrapper<SysUser>()
            .eq("user_id", userId)
            .set("group_id", target.getGroupId())
            .set("group_name", target.getGroupName())
            .set("updated_at", target.getUpdatedAt()));
        auditLogService.logUpdate("sys_user", userId, oldSnapshot, target, currentUsername());

        String roleCode = userRoleService.getRoleCodesByUserId(userId).stream().findFirst().orElse(null);
        return toMemberResponse(target, company.getOwnerId(), roleCode);
    }

    /**
     * 认证设计师：当前用户一键升级（rooom TOURIST → DESIGNER 映射）。
     * 挂 certified_designer 标记；当前角色为 VIEWER/USER 时补 DESIGNER 角色并使旧 token 失效。
     */
    @Transactional
    public void certifiedDesigner() {
        String userId = currentUserIdRequired();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new ResourceNotFoundException("当前用户不存在");
        }
        if (Boolean.TRUE.equals(user.getCertifiedDesigner())) {
            throw new BusinessException("您已是认证设计师");
        }

        SysUser oldSnapshot = snapshot(user);
        user.setCertifiedDesigner(true);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);

        List<String> roleCodes = userRoleService.getRoleCodesByUserId(userId);
        if (!roleCodes.contains(DESIGNER_ROLE) && !roleCodes.contains("ADMIN") && !roleCodes.contains("EDITOR")) {
            userRoleService.assignRoleByCode(userId, DESIGNER_ROLE);
            incrementTokenVersion(userId);
        }
        auditLogService.logUpdate("sys_user", userId, oldSnapshot, snapshot(user), currentUsername());
    }

    private SysUser getMemberInCompany(String userId, Company company) {
        SysUser target = sysUserMapper.selectById(userId);
        if (target == null || !company.getCompanyId().equals(target.getCompanyId())) {
            throw new ResourceNotFoundException("成员不存在: " + userId);
        }
        return target;
    }

    private MemberGroup getEnabledGroupInCompany(String groupId, String companyId) {
        MemberGroup group = memberGroupMapper.selectById(groupId);
        if (group == null || !companyId.equals(group.getCompanyId())) {
            throw new ResourceNotFoundException("分组不存在: " + groupId);
        }
        if (!Boolean.TRUE.equals(group.getEnabled())) {
            throw new BusinessException("分组已停用: " + group.getGroupName());
        }
        return group;
    }

    private Map<String, String> batchFirstRoleCode(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<SysUserRole> userRoles = sysUserRoleMapper.selectList(
            new QueryWrapper<SysUserRole>().in("user_id", userIds));
        List<Long> roleIds = userRoles.stream().map(SysUserRole::getRoleId).distinct().toList();
        Map<Long, String> codeMap = roleIds.isEmpty()
            ? Map.of()
            : sysRoleMapper.selectBatchIds(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getRoleId, SysRole::getRoleCode, (a, b) -> a));
        return userRoles.stream()
            .filter(ur -> codeMap.containsKey(ur.getRoleId()))
            .collect(Collectors.toMap(SysUserRole::getUserId, ur -> codeMap.get(ur.getRoleId()), (a, b) -> a));
    }

    /**
     * 递增用户 token 版本号，使已签发的 JWT 失效（角色升级后强制重新登录）。
     *
     * @param userId 用户 ID
     */
    private void incrementTokenVersion(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return;
        }
        Integer version = user.getTokenVersion();
        user.setTokenVersion(version == null ? 1 : version + 1);
        user.setUpdatedAt(LocalDateTime.now());
        sysUserMapper.updateById(user);
    }

    private String currentUserIdRequired() {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("无法获取当前用户 ID");
        }
        return userId;
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private SysUser snapshot(SysUser source) {
        SysUser copy = new SysUser();
        copy.setUserId(source.getUserId());
        copy.setUsername(source.getUsername());
        copy.setNickname(source.getNickname());
        copy.setCompanyId(source.getCompanyId());
        copy.setCompanyName(source.getCompanyName());
        copy.setGroupId(source.getGroupId());
        copy.setGroupName(source.getGroupName());
        copy.setCertifiedDesigner(source.getCertifiedDesigner());
        copy.setStatus(source.getStatus());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private CompanyMemberResponse toMemberResponse(SysUser user, String ownerId, String roleCode) {
        CompanyMemberResponse response = new CompanyMemberResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setGroupId(user.getGroupId());
        response.setGroupName(user.getGroupName());
        response.setStatus(user.getStatus());
        response.setRoleCode(roleCode);
        response.setCertifiedDesigner(Boolean.TRUE.equals(user.getCertifiedDesigner()));
        response.setOwner(user.getUserId().equals(ownerId));
        response.setCreatedAt(user.getCreatedAt());
        return response;
    }

    private MemberSearchResponse toSearchResponse(SysUser user) {
        MemberSearchResponse response = new MemberSearchResponse();
        response.setUserId(user.getUserId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setStatus(user.getStatus());
        return response;
    }
}
