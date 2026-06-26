package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * RSKU 报价创建请求。
 */
@Data
public class RskuCreateRequest {

    private String rspuId;

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

    @NotBlank(message = "变体 ID 不能为空")
    private String variantId;
    private String factorySku;

    @NotNull(message = "出厂价不能为空")
    private BigDecimal factoryPrice;

    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
    private String quoteConfidence;
}
