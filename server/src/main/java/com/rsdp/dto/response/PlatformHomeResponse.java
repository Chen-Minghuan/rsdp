package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 官网首页聚合响应（免登录）。
 */
@Data
public class PlatformHomeResponse {

    private List<HomeBannerItem> banners;
    private List<HomeCaseItem> cases;
    private List<HomeCustomizedItem> customizeds;

    /**
     * 首页 Banner 项。
     */
    @Data
    public static class HomeBannerItem {
        private String bannerId;
        private String title;
        private String imageUrl;
        private String linkType;
        private String linkValue;
    }

    /**
     * 首页落地案例项。
     */
    @Data
    public static class HomeCaseItem {
        private String caseId;
        private String title;
        private String coverImageUrl;
        private String content;
    }

    /**
     * 首页产品定制项。
     */
    @Data
    public static class HomeCustomizedItem {
        private String customizedId;
        private String title;
        private String coverImageUrl;
        private String description;
        private String linkValue;
    }
}
