package com.rsdp.dto.request;

import lombok.Data;

/**
 * RSPU-工厂关联创建/更新请求。
 */
@Data
public class RspuFactoryMappingRequest {

    private Long mappingId;

    private String rspuId;

    private String factoryCode;

    private Boolean isPrimary;
    private String shippingWarehouseId;
    private Integer moq;
    private Integer baseLeadTimeDays;
    private String status;
    private String notes;
}
