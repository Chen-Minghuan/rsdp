package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.MemberGroupRequest;
import com.rsdp.dto.response.MemberGroupResponse;
import com.rsdp.entity.Company;
import com.rsdp.entity.MemberGroup;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.MemberGroupMapper;
import com.rsdp.mapper.SysUserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link MemberGroupService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class MemberGroupServiceTest {

    @Mock
    private MemberGroupMapper memberGroupMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private CompanyService companyService;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private MemberGroupService memberGroupService;

    private Company company;

    @BeforeEach
    void setUp() {
        company = new Company();
        company.setCompanyId("COM-1");
        company.setCompanyName("示例设计工作室");
        company.setOwnerId("owner-1");
        lenient().when(companyService.getMyCompanyRequired()).thenReturn(company);
    }

    private MemberGroup group(String id, String name) {
        MemberGroup group = new MemberGroup();
        group.setGroupId(id);
        group.setCompanyId("COM-1");
        group.setGroupName(name);
        group.setEnabled(true);
        return group;
    }

    private MemberGroupRequest request(String name) {
        MemberGroupRequest request = new MemberGroupRequest();
        request.setGroupName(name);
        return request;
    }

    @Test
    void listMyGroupsShouldReturnWithMemberCounts() {
        when(memberGroupMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(group("GRP-1", "方案一组"), group("GRP-2", "方案二组")));
        when(sysUserMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of(
            java.util.Map.of("group_id", "GRP-1", "cnt", 2L)));

        List<MemberGroupResponse> groups = memberGroupService.listMyGroups();

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).getMemberCount()).isEqualTo(2);
        assertThat(groups.get(1).getMemberCount()).isEqualTo(0);
    }

    @Test
    void createGroupShouldInsert() {
        when(memberGroupMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        MemberGroupResponse response = memberGroupService.createGroup(request("新分组"));

        assertThat(response.getGroupName()).isEqualTo("新分组");
        assertThat(response.getEnabled()).isTrue();
        verify(memberGroupMapper).insert(any(MemberGroup.class));
        verify(auditLogService).logCreate(eq("member_group"), anyString(), any(MemberGroup.class), any());
    }

    @Test
    void createGroupShouldRejectDuplicateName() {
        when(memberGroupMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThatThrownBy(() -> memberGroupService.createGroup(request("方案一组")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("同名分组已存在");
        verify(memberGroupMapper, never()).insert(any(MemberGroup.class));
    }

    @Test
    void createGroupShouldRejectNonAdmin() {
        doThrow(new ForbiddenException("仅企业管理员可执行该操作"))
            .when(companyService).assertCompanyAdmin(company);

        assertThatThrownBy(() -> memberGroupService.createGroup(request("新分组")))
            .isInstanceOf(ForbiddenException.class);
        verify(memberGroupMapper, never()).insert(any(MemberGroup.class));
    }

    @Test
    void updateGroupShouldRenameAndSyncMemberText() {
        when(memberGroupMapper.selectById("GRP-1")).thenReturn(group("GRP-1", "旧名"));
        when(memberGroupMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        MemberGroupRequest update = request("新名");
        update.setEnabled(false);
        MemberGroupResponse response = memberGroupService.updateGroup("GRP-1", update);

        assertThat(response.getGroupName()).isEqualTo("新名");
        assertThat(response.getEnabled()).isFalse();
        verify(memberGroupMapper).updateById(any(MemberGroup.class));
        // 分组改名同步成员轻量文本字段
        verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
        verify(auditLogService).logUpdate(eq("member_group"), eq("GRP-1"), any(), any(MemberGroup.class), any());
    }

    @Test
    void updateGroupShouldRejectGroupFromOtherCompany() {
        MemberGroup other = group("GRP-9", "他司分组");
        other.setCompanyId("COM-2");
        when(memberGroupMapper.selectById("GRP-9")).thenReturn(other);

        assertThatThrownBy(() -> memberGroupService.updateGroup("GRP-9", request("新名")))
            .isInstanceOf(ResourceNotFoundException.class);
        verify(memberGroupMapper, never()).updateById(any(MemberGroup.class));
    }

    @Test
    void deleteGroupShouldClearMembers() {
        when(memberGroupMapper.selectById("GRP-1")).thenReturn(group("GRP-1", "方案一组"));

        memberGroupService.deleteGroup("GRP-1");

        verify(memberGroupMapper).deleteById("GRP-1");
        verify(sysUserMapper).update(any(), any(UpdateWrapper.class));
        verify(auditLogService).logDelete(eq("member_group"), eq("GRP-1"), any(), any());
    }
}
