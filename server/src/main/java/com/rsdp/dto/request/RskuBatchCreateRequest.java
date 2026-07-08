package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批量创建 RSKU 报价请求。
 */
@Data
public class RskuBatchCreateRequest {

    @NotBlank(message = "变体 ID 不能为空")
    private String variantId;

    @NotEmpty(message = "至少选择一个工厂")
    private List<@NotBlank(message = "工厂代码不能为空") String> factoryCodes;

    @NotNull(message = "出厂价不能为空")
    private BigDecimal factoryPrice;

    private String factorySku;
    private String materialCode;
    private String materialDescription;
    private Integer leadTimeDays;
    private Integer moq;
    private Integer warrantyYears;
    private String shippingFrom;
    private String diffNotes;
    private String quoteConfidence;

    /** 产品等级，为空时按 变体 > RSPU 继承。 */
    private String productLevel;

    /** 工厂无对应能力等级时，是否自动扩展工厂能力。 */
    private Boolean autoExtendCapability;
}
