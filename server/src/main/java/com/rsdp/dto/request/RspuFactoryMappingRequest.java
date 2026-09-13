package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * RSPU-工厂关联创建/更新请求。
 *
 * <p>rspuId 由 Controller 从路径变量回填（在 {@code @Valid} 校验之后），故不在此加
 * {@code @NotBlank}，由 Service 层统一做空值校验。</p>
 */
@Data
public class RspuFactoryMappingRequest {

    private Long mappingId;

    private String rspuId;

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

    private Boolean isPrimary;
    private String shippingWarehouseId;
    private Integer moq;
    private Integer baseLeadTimeDays;
    private String status;
    private String notes;
}
