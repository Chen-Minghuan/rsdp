package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformHomeResponse;
import com.rsdp.entity.PlatformBanner;
import com.rsdp.entity.PlatformCase;
import com.rsdp.entity.PlatformContent;
import com.rsdp.entity.PlatformCustomized;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.PlatformBannerMapper;
import com.rsdp.mapper.PlatformCaseMapper;
import com.rsdp.mapper.PlatformContentMapper;
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
import static org.mockito.Mockito.when;

/**
 * {@link PlatformPublicService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PlatformPublicServiceTest {

    @Mock
    private PlatformBannerMapper bannerMapper;

    @Mock
    private PlatformCaseMapper caseMapper;

    @Mock
    private PlatformCustomizedMapper customizedMapper;

    @Mock
    private PlatformContentMapper contentMapper;

    @InjectMocks
    private PlatformPublicService platformPublicService;

    @Test
    void homeShouldAggregateActiveItemsWithImageUrls() {
        PlatformBanner banner = new PlatformBanner();
        banner.setBannerId("BAN-1");
        banner.setTitle("主图");
        banner.setImageId("IMG-1");
        banner.setLinkType("rspu");
        banner.setLinkValue("RSPU-1");
        when(bannerMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(banner));

        PlatformCase caseEntity = new PlatformCase();
        caseEntity.setCaseId("CASE-1");
        caseEntity.setTitle("杭州案例");
        caseEntity.setCoverImageId("IMG-2");
        when(caseMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(caseEntity));

        PlatformCustomized customized = new PlatformCustomized();
        customized.setCustomizedId("CUST-1");
        customized.setTitle("全屋定制");
        customized.setCoverImageId(null);
        when(customizedMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(customized));

        PlatformHomeResponse home = platformPublicService.home();

        assertThat(home.getBanners()).hasSize(1);
        assertThat(home.getBanners().get(0).getImageUrl()).isEqualTo("/api/v1/images/IMG-1");
        assertThat(home.getBanners().get(0).getLinkType()).isEqualTo("rspu");
        assertThat(home.getCases()).hasSize(1);
        assertThat(home.getCases().get(0).getCoverImageUrl()).isEqualTo("/api/v1/images/IMG-2");
        assertThat(home.getCustomizeds()).hasSize(1);
        // 无封面时 imageUrl 为 null
        assertThat(home.getCustomizeds().get(0).getCoverImageUrl()).isNull();
    }

    @Test
    void getContentByCodeShouldReturnContent() {
        PlatformContent entity = new PlatformContent();
        entity.setContentId("CONT-1");
        entity.setCode("platform_user_agreement");
        entity.setTitle("服务协议");
        entity.setContentType("rich_text");
        entity.setContent("<p>协议</p>");
        when(contentMapper.selectOne(any(QueryWrapper.class))).thenReturn(entity);

        PlatformContentResponse response = platformPublicService.getContentByCode("platform_user_agreement");

        assertThat(response.getCode()).isEqualTo("platform_user_agreement");
        assertThat(response.getContent()).isEqualTo("<p>协议</p>");
    }

    @Test
    void getContentByCodeShouldRejectMissingOrInactive() {
        when(contentMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> platformPublicService.getContentByCode("unknown"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("不存在或已停用");
    }
}
