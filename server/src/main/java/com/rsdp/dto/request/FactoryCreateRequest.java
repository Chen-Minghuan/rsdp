package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工厂创建请求。
 */
@Data
public class FactoryCreateRequest {

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

    @NotBlank(message = "工厂名称不能为空")
    private String factoryName;

    @NotBlank(message = "工厂等级不能为空")
    private String factoryLevel;

    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private String notes;
}
