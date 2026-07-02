package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 工厂等级更新请求。
 */
@Data
public class FactoryLevelUpdateRequest {

    @NotBlank(message = "工厂等级不能为空")
    private String factoryLevel;
}
