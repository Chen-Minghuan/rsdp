package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网落地案例响应。
 */
@Data
public class PlatformCaseResponse {

    private String caseId;
    private String title;
    private String coverImageId;
    private String content;
    private Integer sortOrder;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
