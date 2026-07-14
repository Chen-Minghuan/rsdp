package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收藏响应。
 */
@Data
public class FavoriteResponse {

    private String favoriteId;
    private String rspuId;
    private String groupName;
    /** 产品展示名（取 RSPU 定位标签） */
    private String productName;
    /** 主图访问地址 */
    private String primaryImageUrl;
    private LocalDateTime createdAt;
}
