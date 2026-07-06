package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 更新推荐打分配置请求。
 */
@Data
public class RecommendationScoreConfigUpdateRequest {

    /** 配置名称。 */
    @Size(max = 128, message = "配置名称不能超过 128 个字符")
    private String name;

    /** 描述。 */
    @Size(max = 1000, message = "描述不能超过 1000 个字符")
    private String description;

    /** 权重项。 */
    private Map<String, BigDecimal> weights;

    /** 是否设为默认。 */
    private Boolean isDefault;

    /** 是否启用。 */
    private Boolean isActive;
}
