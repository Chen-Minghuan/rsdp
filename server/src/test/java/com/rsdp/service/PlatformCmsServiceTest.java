package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.request.PlatformBannerRequest;
import com.rsdp.dto.request.PlatformCaseRequest;
import com.rsdp.dto.request.PlatformContentRequest;
import com.rsdp.dto.request.PlatformCustomDictRequest;
import com.rsdp.dto.request.PlatformCustomizedRequest;
import com.rsdp.dto.response.PlatformBannerResponse;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformCustomDictResponse;
import com.rsdp.entity.PlatformBanner;
import com.rsdp.entity.PlatformContent;
import com.rsdp.entity.PlatformCustomDict;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private AuditLogService auditLogService;

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
