package com.rsdp.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * RSKU 价格更新请求。
 */
@Data
public class RskuPriceUpdateRequest {

    @NotNull(message = "新价格不能为空")
    @Positive(message = "价格必须大于 0")
    @DecimalMax(value = "99999999.99", message = "出厂价超出最大允许范围（99999999.99）")
    private BigDecimal factoryPrice;

    private String changeReason;
}
