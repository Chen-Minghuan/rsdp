package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

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

    /**
     * 兼做等级列表，不包含主等级时会自动把主等级加入。
     */
    private List<String> capableLevels;

    private String homeCommercialTag;
    private String region;
    private String address;
    private String contactPerson;
    private String contactPhone;
    private String notes;

    /**
     * 资质认证（JSON 字符串，如 ["ISO9001","FSC"]）。
     */
    private String certification;

    /**
     * 工程案例（JSON 字符串，如 [{"name":"案例1"}]）。
     */
    private String engineeringCases;
}
