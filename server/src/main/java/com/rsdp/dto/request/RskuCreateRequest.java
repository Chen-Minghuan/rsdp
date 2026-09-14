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

    /**
     * 出厂价（Jackson 按 JSON 十进制文本精确反序列化为 BigDecimal，入库前 AES 加密）。
     *
     * <p>精度口径：前端以 JS number 提交，JSON.stringify 输出的是可精确往返的十进制文本，
     * 中间无浮点运算，因此 ≤ 999,999,999.99（两位小数以内）的金额全链路精确；
     * 超过该口径或三位以上小数的金额需改字符串传输后再评估。</p>
     */
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
