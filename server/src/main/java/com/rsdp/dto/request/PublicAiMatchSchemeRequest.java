package com.rsdp.dto.request;

import jakarta.validation.constraints.Min;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 官网 AI 户型搭配方案生成请求（全部字段可空）。
 */
@Data
public class PublicAiMatchSchemeRequest {

    /**
     * 风格偏好（风格字典码），可空。
     */
    private String stylePreference;

    /**
     * 预算上限（元），可空，缺省按不限预算处理。
     */
    @Min(value = 0, message = "预算上限不能为负数")
    private BigDecimal budgetLimit;

    /**
     * 空间宽度（mm），可空。
     */
    @Min(value = 1, message = "空间宽度必须为正数")
    private Integer widthMm;

    /**
     * 空间深度（mm），可空。
     */
    @Min(value = 1, message = "空间深度必须为正数")
    private Integer depthMm;
}
