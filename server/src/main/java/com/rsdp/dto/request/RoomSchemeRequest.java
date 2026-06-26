package com.rsdp.dto.request;

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
}
