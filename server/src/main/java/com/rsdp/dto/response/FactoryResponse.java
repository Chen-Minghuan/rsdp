package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工厂响应。
 */
@Data
public class FactoryResponse {

    private String factoryCode;
    private String factoryName;
    private String factoryLevel;
    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private String notes;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
