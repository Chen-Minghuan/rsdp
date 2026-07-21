package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.MemberGroupRequest;
import com.rsdp.dto.response.MemberGroupResponse;
import com.rsdp.entity.Company;
import com.rsdp.entity.MemberGroup;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.MemberGroupMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 企业分组服务：企业内分组/部门 CRUD 与启停。
 */
@Service
@RequiredArgsConstructor
public class MemberGroupService {

    private final MemberGroupMapper memberGroupMapper;
    private final SysUserMapper sysUserMapper;
    private final CompanyService companyService;
    private final AuditLogService auditLogService;

    /**
     * 查询当前用户企业的分组列表（含成员数）。
     *
     * @return 分组列表
     */
    public List<MemberGroupResponse> listMyGroups() {
        Company company = companyService.getMyCompanyRequired();
        List<MemberGroup> groups = memberGroupMapper.selectList(new QueryWrapper<MemberGroup>()
            .eq("company_id", company.getCompanyId())
            .orderByAsc("created_at"));
        if (groups.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> countMap = batchMemberCounts(
            groups.stream().map(MemberGroup::getGroupId).toList());
        return groups.stream().map(g -> toResponse(g, countMap.getOrDefault(g.getGroupId(), 0))).toList();
    }

    /**
     * 创建分组。仅企业管理员或平台 ADMIN；同企业下分组名称不可重复。
     *
     * @param request 创建请求
     * @return 创建后的分组
     */
    @Transactional
    public MemberGroupResponse createGroup(MemberGroupRequest request) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);
        assertGroupNameUnique(company.getCompanyId(), request.getGroupName().trim(), null);

        MemberGroup group = new MemberGroup();
        group.setGroupId(IdGenerator.generate("GRP"));
        group.setCompanyId(company.getCompanyId());
        group.setGroupName(request.getGroupName().trim());
        group.setEnabled(request.getEnabled() != null ? request.getEnabled() : Boolean.TRUE);
        group.setCreatedAt(LocalDateTime.now());
        group.setUpdatedAt(LocalDateTime.now());
        memberGroupMapper.insert(group);
        auditLogService.logCreate("member_group", group.getGroupId(), group, currentUsername());
        return toResponse(group, 0);
    }

    /**
     * 更新分组（名称/启停）。仅企业管理员或平台 ADMIN。
     *
     * @param groupId 分组 ID
     * @param request 更新请求（仅更新非空字段）
     * @return 更新后的分组
     */
    @Transactional
    public MemberGroupResponse updateGroup(String groupId, MemberGroupRequest request) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);
        MemberGroup group = getGroupInCompany(groupId, company.getCompanyId());
        MemberGroup oldSnapshot = snapshot(group);

        if (StringUtils.hasText(request.getGroupName())) {
            String newName = request.getGroupName().trim();
            if (!newName.equals(group.getGroupName())) {
                assertGroupNameUnique(company.getCompanyId(), newName, groupId);
                group.setGroupName(newName);
                // 分组改名同步成员轻量文本字段
                sysUserMapper.update(null, new UpdateWrapper<SysUser>()
                    .eq("group_id", groupId)
                    .set("group_name", newName)
                    .set("updated_at", LocalDateTime.now()));
            }
        }
        if (request.getEnabled() != null) {
            group.setEnabled(request.getEnabled());
        }
        group.setUpdatedAt(LocalDateTime.now());
        memberGroupMapper.updateById(group);
        auditLogService.logUpdate("member_group", groupId, oldSnapshot, group, currentUsername());
        Long count = sysUserMapper.selectCount(new QueryWrapper<SysUser>().eq("group_id", groupId));
        return toResponse(group, count != null ? count.intValue() : 0);
    }

    /**
     * 软删除分组：分组成员的 group_id 置空。仅企业管理员或平台 ADMIN。
     *
     * @param groupId 分组 ID
     */
    @Transactional
    public void deleteGroup(String groupId) {
        Company company = companyService.getMyCompanyRequired();
        companyService.assertCompanyAdmin(company);
        MemberGroup group = getGroupInCompany(groupId, company.getCompanyId());
        MemberGroup oldSnapshot = snapshot(group);

        memberGroupMapper.deleteById(groupId);
        sysUserMapper.update(null, new UpdateWrapper<SysUser>()
            .eq("group_id", groupId)
            .set("group_id", null)
            .set("updated_at", LocalDateTime.now()));
        auditLogService.logDelete("member_group", groupId, oldSnapshot, currentUsername());
    }

    private MemberGroup getGroupInCompany(String groupId, String companyId) {
        MemberGroup group = memberGroupMapper.selectById(groupId);
        if (group == null || !companyId.equals(group.getCompanyId())) {
            throw new ResourceNotFoundException("分组不存在: " + groupId);
        }
        return group;
    }

    private void assertGroupNameUnique(String companyId, String groupName, String excludeGroupId) {
        QueryWrapper<MemberGroup> wrapper = new QueryWrapper<MemberGroup>()
            .eq("company_id", companyId)
            .eq("group_name", groupName);
        if (StringUtils.hasText(excludeGroupId)) {
            wrapper.ne("group_id", excludeGroupId);
        }
        if (memberGroupMapper.selectCount(wrapper) > 0) {
            throw new BusinessException("同名分组已存在: " + groupName);
        }
    }

    private Map<String, Integer> batchMemberCounts(List<String> groupIds) {
        List<Map<String, Object>> rows = sysUserMapper.selectMaps(new QueryWrapper<SysUser>()
            .select("group_id", "COUNT(*) AS cnt")
            .in("group_id", groupIds)
            .groupBy("group_id"));
        return rows.stream().collect(Collectors.toMap(
            row -> (String) row.get("group_id"),
            row -> ((Number) row.get("cnt")).intValue(),
            (a, b) -> a));
    }

    private String currentUsername() {
        String username = SecurityOperatorContext.currentUsername();
        return StringUtils.hasText(username) ? username : "unknown";
    }

    private MemberGroup snapshot(MemberGroup source) {
        MemberGroup copy = new MemberGroup();
        copy.setGroupId(source.getGroupId());
        copy.setCompanyId(source.getCompanyId());
        copy.setGroupName(source.getGroupName());
        copy.setEnabled(source.getEnabled());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private MemberGroupResponse toResponse(MemberGroup group, int memberCount) {
        MemberGroupResponse response = new MemberGroupResponse();
        response.setGroupId(group.getGroupId());
        response.setCompanyId(group.getCompanyId());
        response.setGroupName(group.getGroupName());
        response.setEnabled(group.getEnabled());
        response.setMemberCount(memberCount);
        response.setCreatedAt(group.getCreatedAt());
        return response;
    }
}
