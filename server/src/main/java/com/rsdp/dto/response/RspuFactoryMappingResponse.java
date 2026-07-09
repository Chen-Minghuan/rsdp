package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * RSPU-工厂关联响应。
 */
@Data
public class RspuFactoryMappingResponse {

    private Long mappingId;
    private String rspuId;
    private String factoryCode;
    private String factoryName;
    private String factoryLevel;
    private Boolean isPrimary;
    private String shippingWarehouseId;
    private String warehouseName;
    private String province;
    private String city;
    private Integer moq;
    private Integer baseLeadTimeDays;
    private String status;
    private String notes;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
