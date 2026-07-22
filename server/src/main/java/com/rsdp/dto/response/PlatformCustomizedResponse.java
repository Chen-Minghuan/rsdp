package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网产品定制响应。
 */
@Data
public class PlatformCustomizedResponse {

    private String customizedId;
    private String title;
    private String coverImageId;
    private String description;
    private String linkValue;
    private Integer sortOrder;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
