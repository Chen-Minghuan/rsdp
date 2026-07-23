package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 项目画布分享公开视图（免登录只读）。
 *
 * <p>安全边界：只含空间分区/产品名/图片/数量，不含工厂/价格/RSKU 等敏感信息。</p>
 */
@Data
public class ProjectShareResponse {

    private String projectId;
    private String projectName;
    private String companyName;
    private String remark;
    private LocalDateTime shareExpireAt;
    private List<ShareScheme> schemes;

    /**
     * 分享视图中的方案。
     */
    @Data
    public static class ShareScheme {
        private String schemeId;
        private String schemeName;
        private Integer itemCount;
        private List<ShareItem> items;
    }

    /**
     * 分享视图中的方案明细。
     */
    @Data
    public static class ShareItem {
        private String rspuId;
        private String productName;
        private String imageId;
        private Integer quantity;
        /** 空间分区标签（RSPU 首个场景标签名） */
        private String spaceTag;
    }
}
