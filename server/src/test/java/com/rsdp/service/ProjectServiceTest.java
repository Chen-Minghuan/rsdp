package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.rsdp.common.PageResult;
import com.rsdp.dto.request.ProjectRequest;
import com.rsdp.dto.response.ProjectDetailResponse;
import com.rsdp.dto.response.ProjectResponse;
import com.rsdp.entity.Project;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SysUser;
import com.rsdp.exception.ForbiddenException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.SchemeMapper;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ProjectService projectService;

    private Project ownedProject() {
        Project project = new Project();
        project.setProjectId("PROJ-1");
        project.setProjectName("滨江一号全屋");
        project.setOwnerId("user-1");
        project.setStatus("active");
        return project;
    }

    @Test
    void createShouldSetOwnerAndGeneratedId() {
        ProjectRequest request = new ProjectRequest();
        request.setProjectName("滨江一号全屋");
        request.setProjectType("whole_house");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ProjectResponse response = projectService.create(request);

            assertThat(response.getProjectName()).isEqualTo("滨江一号全屋");
            assertThat(response.getOwnerId()).isEqualTo("user-1");
            assertThat(response.getStatus()).isEqualTo("active");
        }

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(captor.capture());
        assertThat(captor.getValue().getProjectId()).startsWith("PROJ-");
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
    }

    @Test
    void createShouldFallbackToUserCompanyWhenRequestCompanyBlank() {
        ProjectRequest request = new ProjectRequest();
        request.setProjectName("滨江一号全屋");
        SysUser user = new SysUser();
        user.setUserId("user-1");
        user.setCompanyName("示例设计工作室");
        when(sysUserMapper.selectById("user-1")).thenReturn(user);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ProjectResponse response = projectService.create(request);

            assertThat(response.getCompanyName()).isEqualTo("示例设计工作室");
        }

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(captor.capture());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("示例设计工作室");
    }

    @Test
    void createShouldPreferRequestCompanyOverUserCompany() {
        ProjectRequest request = new ProjectRequest();
        request.setProjectName("滨江一号全屋");
        request.setCompanyName("指定企业");

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ProjectResponse response = projectService.create(request);

            assertThat(response.getCompanyName()).isEqualTo("指定企业");
        }

        ArgumentCaptor<Project> captor = ArgumentCaptor.forClass(Project.class);
        verify(projectMapper).insert(captor.capture());
        assertThat(captor.getValue().getCompanyName()).isEqualTo("指定企业");
    }

    @Test
    void createShouldSetNullCompanyWhenUserHasNoCompany() {
        ProjectRequest request = new ProjectRequest();
        request.setProjectName("滨江一号全屋");
        SysUser user = new SysUser();
        user.setUserId("user-1");
        when(sysUserMapper.selectById("user-1")).thenReturn(user);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            ProjectResponse response = projectService.create(request);

            assertThat(response.getCompanyName()).isNull();
        }
    }

    @Test
    void detailShouldAggregateSchemes() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(ownedProject());
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCH-1");
        scheme.setSchemeName("客厅方案");
        scheme.setTotalPrice(new BigDecimal("12000.00"));
        when(schemeMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(scheme));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            ProjectDetailResponse detail = projectService.detail("PROJ-1");

            assertThat(detail.getSchemes()).hasSize(1);
            assertThat(detail.getSchemeCount()).isEqualTo(1);
            assertThat(detail.getTotalPrice()).isEqualByComparingTo("12000.00");
        }
    }

    @Test
    void detailShouldRejectOtherUsersProject() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(ownedProject());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-2");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            assertThatThrownBy(() -> projectService.detail("PROJ-1"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("无权访问");
        }
    }

    @Test
    void detailShouldAllowAdminBypass() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(ownedProject());
        when(schemeMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("admin-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            ProjectDetailResponse detail = projectService.detail("PROJ-1");

            assertThat(detail.getProjectId()).isEqualTo("PROJ-1");
        }
    }

    @Test
    void detailShouldRejectMissingProject() {
        when(projectMapper.selectById("PROJ-X")).thenReturn(null);

        assertThatThrownBy(() -> projectService.detail("PROJ-X"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("项目不存在");
    }

    @Test
    void deleteShouldSoftDeleteAndDetachSchemes() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(ownedProject());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            projectService.delete("PROJ-1");
        }

        verify(projectMapper).deleteById("PROJ-1");
        verify(schemeMapper).update(eq(null), any(UpdateWrapper.class));
    }

    @Test
    void listShouldScopeToOwnerForNonAdmin() {
        Page<Project> page = Page.of(1, 10);
        page.setRecords(List.of(ownedProject()));
        page.setTotal(1);
        when(projectMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);
        when(schemeMapper.selectMaps(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            PageResult<ProjectResponse> result = projectService.list(null, null, 1, 10);

            assertThat(result.getTotal()).isEqualTo(1);
            assertThat(result.getRows()).hasSize(1);
            assertThat(result.getRows().get(0).getProjectName()).isEqualTo("滨江一号全屋");
        }
    }

    @Test
    void listShouldApplyOwnerFilterWhenAdminChoosesMine() {
        Page<Project> page = Page.of(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(projectMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("admin-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            projectService.list(null, "mine", 1, 10);
        }

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).contains("owner_id");
    }

    @Test
    void listShouldSkipOwnerFilterWhenAdminChoosesAll() {
        Page<Project> page = Page.of(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(projectMapper.selectPage(any(Page.class), any(QueryWrapper.class))).thenReturn(page);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("admin-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(true);

            projectService.list(null, "all", 1, 10);
        }

        ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(projectMapper).selectPage(any(Page.class), captor.capture());
        assertThat(captor.getValue().getSqlSegment()).doesNotContain("owner_id");
    }

    @Test
    void updateShareShouldEnableWithExpireDays() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(ownedProject());

        com.rsdp.dto.request.ProjectShareRequest request = new com.rsdp.dto.request.ProjectShareRequest();
        request.setShareEnabled(true);
        request.setExpireDays(7);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            com.rsdp.dto.response.ProjectResponse response = projectService.updateShare("PROJ-1", request);

            assertThat(response.getShareEnabled()).isTrue();
            assertThat(response.getShareExpireAt()).isNotNull();
            assertThat(response.getShareExpireAt()).isAfter(java.time.LocalDateTime.now().plusDays(6));
            verify(projectMapper).updateById(any(Project.class));
            verify(auditLogService).logUpdate(eq("project"), eq("PROJ-1"), any(), any(Project.class), any());
        }
    }

    @Test
    void updateShareShouldDisableAndClearExpireAt() {
        Project project = ownedProject();
        project.setShareEnabled(true);
        project.setShareExpireAt(java.time.LocalDateTime.now().plusDays(7));
        when(projectMapper.selectById("PROJ-1")).thenReturn(project);

        com.rsdp.dto.request.ProjectShareRequest request = new com.rsdp.dto.request.ProjectShareRequest();
        request.setShareEnabled(false);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.isCurrentUserAdmin()).thenReturn(false);

            com.rsdp.dto.response.ProjectResponse response = projectService.updateShare("PROJ-1", request);

            assertThat(response.getShareEnabled()).isFalse();
            assertThat(response.getShareExpireAt()).isNull();
        }
    }
}
