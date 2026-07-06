package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂产品能力档案响应。
 */
@Data
public class FactoryProductCapabilityResponse {

    private Long id;
    private String factoryCode;
    private String categoryCode;
    private String styleCode;
    private String materialCode;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
