package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 官网自定义字典响应。
 */
@Data
public class PlatformCustomDictResponse {

    private String dictId;
    private String dictName;
    private String dictType;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
