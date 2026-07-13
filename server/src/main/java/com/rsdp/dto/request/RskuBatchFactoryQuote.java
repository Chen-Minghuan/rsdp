package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 批量创建 RSKU 时，为单个工厂录入的统一报价信息。
 *
 * <p>该报价会应用到请求中所有选中的变体。</p>
 */
@Data
public class RskuBatchFactoryQuote {

    @NotBlank(message = "工厂代码不能为空")
    private String factoryCode;

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
    private String diffNotes;
    private String quoteConfidence;
}
