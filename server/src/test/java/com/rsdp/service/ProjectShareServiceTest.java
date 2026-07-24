package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.ProjectShareResponse;
import com.rsdp.entity.Project;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.Scheme;
import com.rsdp.entity.SchemeItem;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.ProjectMapper;
import com.rsdp.mapper.RspuMapper;
import com.rsdp.mapper.RspuSceneMapper;
import com.rsdp.mapper.SchemeItemMapper;
import com.rsdp.mapper.SchemeMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * {@link ProjectShareService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ProjectShareServiceTest {

    @Mock
    private ProjectMapper projectMapper;

    @Mock
    private SchemeMapper schemeMapper;

    @Mock
    private SchemeItemMapper schemeItemMapper;

    @Mock
    private RspuMapper rspuMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private RspuSceneMapper rspuSceneMapper;

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @InjectMocks
    private ProjectShareService projectShareService;

    private Project sharedProject() {
        Project project = new Project();
        project.setProjectId("PROJ-1");
        project.setProjectName("测试项目");
        project.setShareEnabled(true);
        return project;
    }

    @Test
    void getSharedProjectShouldReturnPublicView() {
        when(projectMapper.selectById("PROJ-1")).thenReturn(sharedProject());
        Scheme scheme = new Scheme();
        scheme.setSchemeId("SCHEME-1");
        scheme.setSchemeName("客厅方案");
        when(schemeMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(scheme));
        SchemeItem item = new SchemeItem();
        item.setSchemeItemId(1L);
        item.setSchemeId("SCHEME-1");
        item.setRspuId("RSPU-001");
        item.setQuantity(2);
        when(schemeItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(item));
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        rspu.setPositioningLabel("北欧布艺沙发");
        when(rspuMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rspu));
        when(imageAssetsMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(rspuSceneMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        ProjectShareResponse response = projectShareService.getSharedProject("PROJ-1");

        assertThat(response.getProjectName()).isEqualTo("测试项目");
        assertThat(response.getSchemes()).hasSize(1);
        assertThat(response.getSchemes().get(0).getItems()).hasSize(1);
        assertThat(response.getSchemes().get(0).getItems().get(0).getProductName()).isEqualTo("北欧布艺沙发");
        assertThat(response.getSchemes().get(0).getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void getSharedProjectShouldRejectWhenShareDisabled() {
        Project project = sharedProject();
        project.setShareEnabled(false);
        when(projectMapper.selectById("PROJ-1")).thenReturn(project);

        assertThatThrownBy(() -> projectShareService.getSharedProject("PROJ-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("不存在或已关闭");
    }

    @Test
    void getSharedProjectShouldRejectWhenExpired() {
        Project project = sharedProject();
        project.setShareExpireAt(LocalDateTime.now().minusDays(1));
        when(projectMapper.selectById("PROJ-1")).thenReturn(project);

        assertThatThrownBy(() -> projectShareService.getSharedProject("PROJ-1"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("已过期");
    }

    @Test
    void getSharedProjectShouldRejectMissingProject() {
        when(projectMapper.selectById("PROJ-X")).thenReturn(null);

        assertThatThrownBy(() -> projectShareService.getSharedProject("PROJ-X"))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
