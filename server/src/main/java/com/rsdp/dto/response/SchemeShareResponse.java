package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 方案分享公开视图（免登录只读，V42）。
 *
 * <p>安全边界：严格白名单组装，只含产品名/主图 imageId/数量/空间名/排序，
 * 不含工厂/价格/RSKU/成本等敏感信息。</p>
 */
@Data
public class SchemeShareResponse {

    private String schemeId;
    private String schemeName;
    /** 分享过期时间（null=永久有效） */
    private LocalDateTime shareExpireAt;
    private List<ShareItem> items;

    /**
     * 分享视图中的方案明细（白名单字段）。
     */
    @Data
    public static class ShareItem {
        private String rspuId;
        private String productName;
        /** 产品主图 image_id（image_assets 主图 is_primary） */
        private String imageId;
        private Integer quantity;
        /** 空间标签显示名（space_tag 覆盖优先，回退产品首场景推导，码已删回退码原文） */
        private String spaceTagName;
        private Integer sortOrder;
    }
}
