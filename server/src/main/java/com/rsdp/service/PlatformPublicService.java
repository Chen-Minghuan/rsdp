package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.dto.response.PlatformContentResponse;
import com.rsdp.dto.response.PlatformHomeResponse;
import com.rsdp.entity.PlatformContent;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.PlatformBannerMapper;
import com.rsdp.mapper.PlatformCaseMapper;
import com.rsdp.mapper.PlatformContentMapper;
import com.rsdp.mapper.PlatformCustomizedMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 官网公开读取服务（免登录）：首页聚合 + 按编码取内容。
 *
 * <p>只返回 status=active 的内容；图片字段统一拼装为 /api/v1/images/{imageId} 访问地址。</p>
 */
@Service
@RequiredArgsConstructor
public class PlatformPublicService {

    /** 首页案例/定制返回上限。 */
    private static final int HOME_CASE_LIMIT = 12;
    private static final int HOME_CUSTOMIZED_LIMIT = 10;

    private final PlatformBannerMapper bannerMapper;
    private final PlatformCaseMapper caseMapper;
    private final PlatformCustomizedMapper customizedMapper;
    private final PlatformContentMapper contentMapper;

    /**
     * 首页聚合：启用 Banner（home_top）+ 启用案例 + 启用产品定制。
     *
     * @return 首页聚合数据
     */
    public PlatformHomeResponse home() {
        PlatformHomeResponse response = new PlatformHomeResponse();

        response.setBanners(bannerMapper.selectList(new QueryWrapper<com.rsdp.entity.PlatformBanner>()
                .eq("position", "home_top")
                .eq("status", "active")
                .orderByAsc("sort_order").orderByDesc("created_at"))
            .stream().map(banner -> {
                PlatformHomeResponse.HomeBannerItem item = new PlatformHomeResponse.HomeBannerItem();
                item.setBannerId(banner.getBannerId());
                item.setTitle(banner.getTitle());
                item.setImageUrl(imageUrl(banner.getImageId()));
                item.setLinkType(banner.getLinkType());
                item.setLinkValue(banner.getLinkValue());
                return item;
            }).toList());

        response.setCases(caseMapper.selectList(new QueryWrapper<com.rsdp.entity.PlatformCase>()
                .eq("status", "active")
                .orderByAsc("sort_order").orderByDesc("created_at")
                .last("LIMIT " + HOME_CASE_LIMIT))
            .stream().map(entity -> {
                PlatformHomeResponse.HomeCaseItem item = new PlatformHomeResponse.HomeCaseItem();
                item.setCaseId(entity.getCaseId());
                item.setTitle(entity.getTitle());
                item.setCoverImageUrl(imageUrl(entity.getCoverImageId()));
                item.setContent(entity.getContent());
                return item;
            }).toList());

        response.setCustomizeds(customizedMapper.selectList(new QueryWrapper<com.rsdp.entity.PlatformCustomized>()
                .eq("status", "active")
                .orderByAsc("sort_order").orderByDesc("created_at")
                .last("LIMIT " + HOME_CUSTOMIZED_LIMIT))
            .stream().map(entity -> {
                PlatformHomeResponse.HomeCustomizedItem item = new PlatformHomeResponse.HomeCustomizedItem();
                item.setCustomizedId(entity.getCustomizedId());
                item.setTitle(entity.getTitle());
                item.setCoverImageUrl(imageUrl(entity.getCoverImageId()));
                item.setDescription(entity.getDescription());
                item.setLinkValue(entity.getLinkValue());
                return item;
            }).toList());

        return response;
    }

    /**
     * 按编码读取内容配置（仅启用状态；服务协议/客服咨询等）。
     *
     * @param code 内容编码
     * @return 内容配置
     */
    public PlatformContentResponse getContentByCode(String code) {
        PlatformContent entity = contentMapper.selectOne(new QueryWrapper<PlatformContent>()
            .eq("code", code)
            .eq("status", "active"));
        if (entity == null) {
            throw new ResourceNotFoundException("内容不存在或已停用: " + code);
        }
        PlatformContentResponse response = new PlatformContentResponse();
        org.springframework.beans.BeanUtils.copyProperties(entity, response);
        return response;
    }

    private String imageUrl(String imageId) {
        return StringUtils.hasText(imageId) ? "/api/v1/images/" + imageId : null;
    }
}
