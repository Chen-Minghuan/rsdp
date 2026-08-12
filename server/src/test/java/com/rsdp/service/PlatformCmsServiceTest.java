package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.dto.request.PlatformBannerRequest;
import com.rsdp.dto.request.PlatformCaseRequest;
import com.rsdp.dto.request.PlatformContentRequest;
import com.rsdp.dto.request.PlatformCustomDictRequest;
import com.rsdp.dto.request.PlatformCustomizedRequest;
import com.rsdp.dto.request.SceneCoverUpdateRequest;
import com.rsdp.dto.response.PlatformBannerResponse;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformCustomDictResponse;
import com.rsdp.dto.response.SceneCoverResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.ImageAssets;
import com.rsdp.entity.PlatformBanner;
import com.rsdp.entity.PlatformContent;
import com.rsdp.entity.PlatformCustomDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.ImageAssetsMapper;
import com.rsdp.mapper.PlatformBannerMapper;
import com.rsdp.mapper.PlatformCaseMapper;
import com.rsdp.mapper.PlatformContentMapper;
import com.rsdp.mapper.PlatformCustomDictMapper;
import com.rsdp.mapper.PlatformCustomizedMapper;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlatformCmsService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlatformCmsServiceTest {

    @Mock
    private PlatformBannerMapper bannerMapper;

    @Mock
    private PlatformCaseMapper caseMapper;

    @Mock
    private PlatformContentMapper contentMapper;

    @Mock
    private PlatformCustomDictMapper customDictMapper;

    @Mock
    private PlatformCustomizedMapper customizedMapper;

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @Mock
    private ImageAssetsMapper imageAssetsMapper;

    @Mock
    private AuditLogService auditLogService;

    // ==================== 空间场景封面（V35） ====================

    @Test
    void listSceneCoversShouldReturnAllSceneDicts() {
        CategoryDict living = new CategoryDict();
        living.setDictType("scene");
        living.setDictCode("LIVING");
        living.setDictName("客厅");
        living.setImageId("IMG-1");
        CategoryDict study = new CategoryDict();
        study.setDictType("scene");
        study.setDictCode("STUDY");
        study.setDictName("书房");
        when(categoryDictMapper.selectAllByType("scene")).thenReturn(List.of(living, study));

        List<SceneCoverResponse> covers = platformCmsService.listSceneCovers();

        assertThat(covers).hasSize(2);
        assertThat(covers.get(0).getCode()).isEqualTo("LIVING");
        assertThat(covers.get(0).getImageId()).isEqualTo("IMG-1");
        assertThat(covers.get(0).getImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(covers.get(1).getImageId()).isNull();
        assertThat(covers.get(1).getImageUrl()).isNull();
    }

    @Test
    void updateSceneCoverShouldSetCover() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("LIVING");
        scene.setDictName("客厅");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(scene);
        ImageAssets image = new ImageAssets();
        image.setImageId("IMG-9");
        when(imageAssetsMapper.selectById("IMG-9")).thenReturn(image);

        SceneCoverUpdateRequest request = new SceneCoverUpdateRequest();
        request.setImageId("IMG-9");

        SceneCoverResponse response = platformCmsService.updateSceneCover("LIVING", request);

        assertThat(response.getImageId()).isEqualTo("IMG-9");
        assertThat(response.getImageUrl()).isEqualTo("/api/v1/images/IMG-9");
        verify(categoryDictMapper).update(isNull(), any(UpdateWrapper.class));
        verify(auditLogService).logUpdate(eq("category_dict"), eq("scene:LIVING"), any(), any(CategoryDict.class), any());
    }

    @Test
    void updateSceneCoverNullImageShouldClearCover() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("LIVING");
        scene.setDictName("客厅");
        scene.setImageId("IMG-9");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(scene);

        SceneCoverUpdateRequest request = new SceneCoverUpdateRequest();
        request.setImageId(null);

        SceneCoverResponse response = platformCmsService.updateSceneCover("LIVING", request);

        assertThat(response.getImageId()).isNull();
        assertThat(response.getImageUrl()).isNull();
        verify(imageAssetsMapper, never()).selectById(anyString());
        verify(categoryDictMapper).update(isNull(), any(UpdateWrapper.class));
        verify(auditLogService).logUpdate(eq("category_dict"), eq("scene:LIVING"), any(), any(CategoryDict.class), any());
    }

    @Test
    void updateSceneCoverShouldRejectNonSceneDict() {
        // 按 dict_type=scene + dict_code 查询不到（编码不存在或属于其他字典类型）
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        SceneCoverUpdateRequest request = new SceneCoverUpdateRequest();
        request.setImageId("IMG-9");

        assertThatThrownBy(() -> platformCmsService.updateSceneCover("PE", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("场景字典项不存在");
        verify(categoryDictMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @Test
    void updateSceneCoverShouldRejectMissingImage() {
        CategoryDict scene = new CategoryDict();
        scene.setDictType("scene");
        scene.setDictCode("LIVING");
        scene.setDictName("客厅");
        when(categoryDictMapper.selectOne(any(QueryWrapper.class))).thenReturn(scene);
        when(imageAssetsMapper.selectById("IMG-X")).thenReturn(null);

        SceneCoverUpdateRequest request = new SceneCoverUpdateRequest();
        request.setImageId("IMG-X");

        assertThatThrownBy(() -> platformCmsService.updateSceneCover("LIVING", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("图片不存在");
        verify(categoryDictMapper, never()).update(isNull(), any(UpdateWrapper.class));
    }

    @InjectMocks
    private PlatformCmsService platformCmsService;

    // ==================== Banner ====================

    @Test
    void createBannerShouldApplyDefaults() {
        PlatformBannerRequest request = new PlatformBannerRequest();
        request.setImageId("IMG-1");
        request.setTitle("首页主图");

        PlatformBannerResponse response = platformCmsService.createBanner(request);

        assertThat(response.getPosition()).isEqualTo("home_top");
        assertThat(response.getLinkType()).isEqualTo("none");
        assertThat(response.getStatus()).isEqualTo("active");
        verify(bannerMapper).insert(any(PlatformBanner.class));
        verify(auditLogService).logCreate(eq("platform_banner"), anyString(), any(PlatformBanner.class), any());
    }

    @Test
    void createBannerShouldRejectInvalidLinkType() {
        PlatformBannerRequest request = new PlatformBannerRequest();
        request.setImageId("IMG-1");
        request.setLinkType("javascript");

        assertThatThrownBy(() -> platformCmsService.createBanner(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法跳转类型");
        verify(bannerMapper, never()).insert(any(PlatformBanner.class));
    }

    @Test
    void updateBannerShouldApplyNonNullFields() {
        PlatformBanner existing = new PlatformBanner();
        existing.setBannerId("BAN-1");
        existing.setImageId("IMG-1");
        existing.setLinkType("none");
        existing.setStatus("active");
        when(bannerMapper.selectById("BAN-1")).thenReturn(existing);

        PlatformBannerRequest request = new PlatformBannerRequest();
        request.setImageId("IMG-2");
        request.setLinkType("rspu");
        request.setLinkValue("RSPU-1");
        request.setStatus("inactive");

        PlatformBannerResponse response = platformCmsService.updateBanner("BAN-1", request);

        assertThat(response.getImageId()).isEqualTo("IMG-2");
        assertThat(response.getLinkType()).isEqualTo("rspu");
        assertThat(response.getStatus()).isEqualTo("inactive");
        verify(auditLogService).logUpdate(eq("platform_banner"), eq("BAN-1"), any(), any(PlatformBanner.class), any());
    }

    @Test
    void deleteBannerShouldRejectMissing() {
        when(bannerMapper.selectById("BAN-X")).thenReturn(null);

        assertThatThrownBy(() -> platformCmsService.deleteBanner("BAN-X"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    // ==================== 落地案例 ====================

    @Test
    void createCaseShouldInsert() {
        PlatformCaseRequest request = new PlatformCaseRequest();
        request.setTitle("杭州全屋案例");

        platformCmsService.createCase(request);

        verify(caseMapper).insert(any(com.rsdp.entity.PlatformCase.class));
        verify(auditLogService).logCreate(eq("platform_case"), anyString(), any(com.rsdp.entity.PlatformCase.class), any());
    }

    // ==================== 内容配置 ====================

    @Test
    void createContentShouldRejectDuplicateCode() {
        when(contentMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        PlatformContentRequest request = new PlatformContentRequest();
        request.setCode("platform_user_agreement");

        assertThatThrownBy(() -> platformCmsService.createContent(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("内容编码已存在");
        verify(contentMapper, never()).insert(any(PlatformContent.class));
    }

    @Test
    void updateContentShouldRejectCodeChange() {
        PlatformContent existing = new PlatformContent();
        existing.setContentId("CONT-1");
        existing.setCode("platform_user_agreement");
        existing.setContentType("rich_text");
        when(contentMapper.selectById("CONT-1")).thenReturn(existing);

        PlatformContentRequest request = new PlatformContentRequest();
        request.setCode("new_code");

        assertThatThrownBy(() -> platformCmsService.updateContent("CONT-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不可修改");
        verify(contentMapper, never()).updateById(any(PlatformContent.class));
    }

    @Test
    void updateContentShouldRejectInvalidContentType() {
        PlatformContent existing = new PlatformContent();
        existing.setContentId("CONT-1");
        existing.setCode("abc");
        when(contentMapper.selectById("CONT-1")).thenReturn(existing);

        PlatformContentRequest request = new PlatformContentRequest();
        request.setCode("abc");
        request.setContentType("video");

        assertThatThrownBy(() -> platformCmsService.updateContent("CONT-1", request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("非法内容类型");
    }

    @Test
    void updateContentShouldUpdateFields() {
        PlatformContent existing = new PlatformContent();
        existing.setContentId("CONT-1");
        existing.setCode("abc");
        existing.setContentType("rich_text");
        when(contentMapper.selectById("CONT-1")).thenReturn(existing);

        PlatformContentRequest request = new PlatformContentRequest();
        request.setCode("abc");
        request.setTitle("新标题");
        request.setContent("<p>新内容</p>");

        PlatformContentResponse response = platformCmsService.updateContent("CONT-1", request);

        assertThat(response.getTitle()).isEqualTo("新标题");
        assertThat(response.getContent()).isEqualTo("<p>新内容</p>");
        verify(auditLogService).logUpdate(eq("platform_content"), eq("CONT-1"), any(), any(PlatformContent.class), any());
    }

    // ==================== 自定义字典 ====================

    @Test
    void createCustomDictShouldRejectDuplicate() {
        when(customDictMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        PlatformCustomDictRequest request = new PlatformCustomDictRequest();
        request.setDictType("banner_position");
        request.setDictName("首页顶部");

        assertThatThrownBy(() -> platformCmsService.createCustomDict(request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("已存在");
        verify(customDictMapper, never()).insert(any(PlatformCustomDict.class));
    }

    @Test
    void updateCustomDictShouldSucceed() {
        PlatformCustomDict existing = new PlatformCustomDict();
        existing.setDictId("PDIC-1");
        existing.setDictType("banner_position");
        existing.setDictName("旧名");
        when(customDictMapper.selectById("PDIC-1")).thenReturn(existing);
        when(customDictMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        PlatformCustomDictRequest request = new PlatformCustomDictRequest();
        request.setDictType("banner_position");
        request.setDictName("新名");

        PlatformCustomDictResponse response = platformCmsService.updateCustomDict("PDIC-1", request);

        assertThat(response.getDictName()).isEqualTo("新名");
        verify(auditLogService).logUpdate(eq("platform_custom_dict"), eq("PDIC-1"), any(), any(PlatformCustomDict.class), any());
    }

    // ==================== 产品定制 ====================

    @Test
    void createCustomizedShouldInsert() {
        PlatformCustomizedRequest request = new PlatformCustomizedRequest();
        request.setTitle("全屋定制");

        platformCmsService.createCustomized(request);

        verify(customizedMapper).insert(any(com.rsdp.entity.PlatformCustomized.class));
        verify(auditLogService).logCreate(eq("platform_customized"), anyString(), any(com.rsdp.entity.PlatformCustomized.class), any());
    }
}
