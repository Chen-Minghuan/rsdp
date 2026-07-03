package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 工厂响应。
 */
@Data
public class FactoryResponse {

    private String factoryCode;
    private String factoryName;

    /**
     * 主评级。
     */
    private String factoryLevel;

    /**
     * 可承接的所有等级（包含主等级）。
     */
    private List<String> capableLevels;

    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private String notes;

    /**
     * 资质认证（JSON 字符串）。
     */
    private String certification;

    /**
     * 工程案例（JSON 字符串）。
     */
    private String engineeringCases;

    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
