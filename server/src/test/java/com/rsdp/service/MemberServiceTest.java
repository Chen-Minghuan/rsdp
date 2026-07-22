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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemberService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysRoleMapper sysRoleMapper;

    @Mock
    private SysUserRoleMapper sysUserRoleMapper;

    @Mock
    private MemberGroupMapper memberGroupMapper;

    @Mock
    private CompanyService companyService;

    @Mock
    private UserRoleService userRoleService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MemberService memberService;

    private Company company;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setCompanyId("COM-1");
        company.setCompanyName("示例设计工作室");
        company.setOwnerId("owner-1");
        lenient().when(companyService.getMyCompanyRequired()).thenReturn(company);
    }

    private SysUser member(String userId) {
        SysUser user = new SysUser();
        user.setUserId(userId);
        user.setUsername(userId);
        user.setNickname("昵称" + userId);
        user.setCompanyId("COM-1");
        user.setCompanyName("示例设计工作室");
        user.setStatus("active");
        return user;
    }

    private MemberGroup group(String id, boolean enabled) {
        MemberGroup group = new MemberGroup();
        group.setGroupId(id);
        group.setCompanyId("COM-1");
        group.setGroupName("方案一组");
        group.setEnabled(enabled);
        return group;
    }

    @Test
    void listMembersShouldReturnWithOwnerFlagAndRole() {
        when(sysUserMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(member("owner-1"), member("user-2")));
        SysUserRole ur = new SysUserRole();
        ur.setUserId("owner-1");
        ur.setRoleId(1L);
        when(sysUserRoleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(ur));
        SysRole role = new SysRole();
        role.setRoleId(1L);
        role.setRoleCode("DESIGNER");
        when(sysRoleMapper.selectBatchIds(anyList())).thenReturn(List.of(role));

        List<CompanyMemberResponse> members = memberService.listMembers(null);

        assertThat(members).hasSize(2);
        assertThat(members.get(0).getOwner()).isTrue();
        assertThat(members.get(0).getRoleCode()).isEqualTo("DESIGNER");
        assertThat(members.get(1).getOwner()).isFalse();
    }

    @Test
    void searchUsersShouldExcludeSelf() {
        SysUser candidate = new SysUser();
        candidate.setUserId("user-9");
        candidate.setUsername("zhangsan");
        candidate.setStatus("active");
        SysUser self = new SysUser();
        self.setUserId("owner-1");
        self.setUsername("owner");
        self.setStatus("active");
        when(sysUserMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(candidate, self));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("owner-1");

            List<MemberSearchResponse> results = memberService.searchUsers("张");

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getUserId()).isEqualTo("user-9");
        }
    }

    @Test
    void joinCompanyShouldBindCompanyAndGroup() {
        SysUser target = new SysUser();
        target.setUserId("user-9");
        target.setUsername("zhangsan");
        target.setPasswordHash("$2a$10$bcryptHashValue");
        target.setStatus("active");
        when(sysUserMapper.selectById("user-9")).thenReturn(target);
        when(memberGroupMapper.selectById("GRP-1")).thenReturn(group("GRP-1", true));
        when(userRoleService.getRoleCodesByUserId("user-9")).thenReturn(List.of("VIEWER"));

        JoinCompanyRequest request = new JoinCompanyRequest();
        request.setUserId("user-9");
        request.setGroupId("GRP-1");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");

            CompanyMemberResponse response = memberService.joinCompany(request);

            assertThat(response.getGroupId()).isEqualTo("GRP-1");
            assertThat(response.getGroupName()).isEqualTo("方案一组");
            verify(sysUserMapper).updateById(any(SysUser.class));
            ArgumentCaptor<Object> newValueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(auditLogService).logUpdate(eq("sys_user"), eq("user-9"), any(), newValueCaptor.capture(), eq("owner"));
            // 审计 newValue 为脱敏快照，不含密码哈希
            assertThat(((SysUser) newValueCaptor.getValue()).getPasswordHash()).isNull();
        }
    }

    @Test
    void joinCompanyShouldRejectUserInOtherCompany() {
        SysUser target = member("user-9");
        target.setCompanyId("COM-2");
        when(sysUserMapper.selectById("user-9")).thenReturn(target);

        JoinCompanyRequest request = new JoinCompanyRequest();
        request.setUserId("user-9");

        assertThatThrownBy(() -> memberService.joinCompany(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已归属其他企业");
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void joinCompanyShouldRejectDisabledGroup() {
        SysUser target = new SysUser();
        target.setUserId("user-9");
        target.setStatus("active");
        when(sysUserMapper.selectById("user-9")).thenReturn(target);
        when(memberGroupMapper.selectById("GRP-1")).thenReturn(group("GRP-1", false));

        JoinCompanyRequest request = new JoinCompanyRequest();
        request.setUserId("user-9");
        request.setGroupId("GRP-1");

        assertThatThrownBy(() -> memberService.joinCompany(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已停用");
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    @Test
    void removeMemberShouldRejectRemovingOwner() {
        when(sysUserMapper.selectById("owner-1")).thenReturn(member("owner-1"));

        assertThatThrownBy(() -> memberService.removeMember("owner-1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("变更企业管理员");
        verify(sysUserMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void removeMemberShouldClearCompanyBinding() {
        SysUser target = member("user-2");
        target.setPasswordHash("$2a$10$bcryptHashValue");
        target.setGroupId("GRP-1");
        target.setGroupName("方案一组");
        when(sysUserMapper.selectById("user-2")).thenReturn(target);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");

            memberService.removeMember("user-2");

            verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
            ArgumentCaptor<Object> oldValueCaptor = ArgumentCaptor.forClass(Object.class);
            ArgumentCaptor<Object> newValueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(auditLogService).logUpdate(
                eq("sys_user"), eq("user-2"), oldValueCaptor.capture(), newValueCaptor.capture(), eq("owner"));
            SysUser oldValue = (SysUser) oldValueCaptor.getValue();
            SysUser newValue = (SysUser) newValueCaptor.getValue();
            // oldValue 保留移出前归属
            assertThat(oldValue.getCompanyId()).isEqualTo("COM-1");
            assertThat(oldValue.getGroupId()).isEqualTo("GRP-1");
            // newValue 归属字段已清空，且不含密码哈希
            assertThat(newValue.getCompanyId()).isNull();
            assertThat(newValue.getCompanyName()).isNull();
            assertThat(newValue.getGroupId()).isNull();
            assertThat(newValue.getGroupName()).isNull();
            assertThat(newValue.getPasswordHash()).isNull();
        }
    }

    @Test
    void removeMemberShouldRejectNonMember() {
        SysUser outsider = member("user-9");
        outsider.setCompanyId("COM-2");
        when(sysUserMapper.selectById("user-9")).thenReturn(outsider);

        assertThatThrownBy(() -> memberService.removeMember("user-9"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("成员不存在");
    }

    @Test
    void updateMemberGroupShouldClearWhenGroupIdNull() {
        when(sysUserMapper.selectById("user-2")).thenReturn(member("user-2"));
        when(userRoleService.getRoleCodesByUserId("user-2")).thenReturn(List.of("DESIGNER"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUsername()).thenReturn("owner");

            CompanyMemberResponse response = memberService.updateMemberGroup("user-2", new MemberGroupAssignRequest());

            assertThat(response.getGroupId()).isNull();
            verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
        }
    }

    @Test
    void certifiedDesignerShouldUpgradeViewerToDesigner() {
        SysUser user = new SysUser();
        user.setUserId("user-1");
        user.setCertifiedDesigner(false);
        user.setPasswordHash("$2a$10$bcryptHashValue");
        user.setTokenVersion(0);
        when(sysUserMapper.selectById("user-1")).thenReturn(user);
        when(userRoleService.getRoleCodesByUserId("user-1")).thenReturn(List.of("VIEWER"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("viewer");

            memberService.certifiedDesigner();

            assertThat(user.getCertifiedDesigner()).isTrue();
            verify(userRoleService).assignRoleByCode("user-1", "DESIGNER");
            // 角色升级后递增 token_version（selectById 两次 + updateById 两次）
            verify(sysUserMapper, org.mockito.Mockito.times(2)).updateById(any(SysUser.class));
            ArgumentCaptor<Object> newValueCaptor = ArgumentCaptor.forClass(Object.class);
            verify(auditLogService).logUpdate(eq("sys_user"), eq("user-1"), any(), newValueCaptor.capture(), eq("viewer"));
            // 审计 newValue 为脱敏快照，不含密码哈希
            assertThat(((SysUser) newValueCaptor.getValue()).getPasswordHash()).isNull();
        }
    }

    @Test
    void certifiedDesignerShouldRejectWhenAlreadyCertified() {
        SysUser user = new SysUser();
        user.setUserId("user-1");
        user.setCertifiedDesigner(true);
        when(sysUserMapper.selectById("user-1")).thenReturn(user);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> memberService.certifiedDesigner())
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已是认证设计师");
            verify(userRoleService, never()).assignRoleByCode(anyString(), anyString());
        }
    }

    @Test
    void certifiedDesignerShouldKeepRoleWhenAlreadyDesigner() {
        SysUser user = new SysUser();
        user.setUserId("user-1");
        user.setCertifiedDesigner(false);
        when(sysUserMapper.selectById("user-1")).thenReturn(user);
        when(userRoleService.getRoleCodesByUserId("user-1")).thenReturn(List.of("DESIGNER"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");

            memberService.certifiedDesigner();

            assertThat(user.getCertifiedDesigner()).isTrue();
            // 已是 DESIGNER：只挂标记，不重复分配角色、不递增 token_version
            verify(userRoleService, never()).assignRoleByCode(anyString(), anyString());
            verify(sysUserMapper, org.mockito.Mockito.times(1)).updateById(any(SysUser.class));
        }
    }
}
