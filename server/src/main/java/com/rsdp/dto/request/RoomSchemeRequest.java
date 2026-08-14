package com.rsdp.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * AI 空间搭配方案请求。
 */
@Data
public class RoomSchemeRequest {

    @NotBlank(message = "空间类型不能为空")
    private String roomType;

    @NotNull(message = "预算上限不能为空")
    private BigDecimal budgetLimit;

    private String stylePreference;

    /**
     * 空间开间（mm），可空；与 depthMm 同时提供时，prompt 注入空间尺寸上下文
     * （户型图链路 v3.0 §5.3），缺省时行为不变。
     */
    @Min(value = 1, message = "空间宽度必须为正数")
    private Integer widthMm;

    /**
     * 空间进深（mm），可空。
     */
    @Min(value = 1, message = "空间深度必须为正数")
    private Integer depthMm;
}
