package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 创建推荐打分配置请求。
 */
@Data
public class RecommendationScoreConfigCreateRequest {

    /** 配置键。 */
    @NotBlank(message = "配置键不能为空")
    @Size(max = 64, message = "配置键不能超过 64 个字符")
    private String configKey;

    /** 配置名称。 */
    @NotBlank(message = "配置名称不能为空")
    @Size(max = 128, message = "配置名称不能超过 128 个字符")
    private String name;

    /** 描述。 */
    @Size(max = 1000, message = "描述不能超过 1000 个字符")
    private String description;

    /** 权重项，要求各项之和为 1。 */
    @NotEmpty(message = "权重项不能为空")
    private Map<String, BigDecimal> weights;

    /** 是否设为默认。 */
    private Boolean isDefault;

    /** 是否启用。 */
    private Boolean isActive;
}
