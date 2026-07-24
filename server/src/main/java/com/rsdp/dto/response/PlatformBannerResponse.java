package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网 Banner 响应。
 */
@Data
public class PlatformBannerResponse {

    private String bannerId;
    private String position;
    private String title;
    private String imageId;
    private String linkType;
    private String linkValue;
    private Integer sortOrder;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
