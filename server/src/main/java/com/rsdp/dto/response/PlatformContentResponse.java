package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网内容配置响应。
 */
@Data
public class PlatformContentResponse {

    private String contentId;
    private String code;
    private String title;
    private String contentType;
    private String content;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
