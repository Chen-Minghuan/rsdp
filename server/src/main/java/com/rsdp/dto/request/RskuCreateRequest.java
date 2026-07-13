package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
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
    @Positive(message = "出厂价必须大于 0")
    private BigDecimal factoryPrice;

    private String materialCode;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String shippingWarehouseId;
    private String diffNotes;
    private String quoteConfidence;

    /** 产品等级，为空时按 变体 > RSPU 继承。 */
    private String productLevel;

    /** 工厂无对应能力等级时，是否自动扩展工厂能力。 */
    private Boolean autoExtendCapability;
}
