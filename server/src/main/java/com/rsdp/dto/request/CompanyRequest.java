package com.rsdp.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 企业创建/更新请求。
 */
@Data
public class CompanyRequest {

    @NotBlank(message = "企业名称不能为空")
    @Size(max = 128, message = "企业名称不能超过 128 个字符")
    private String companyName;

    @Size(max = 64, message = "Logo 图片 ID 不能超过 64 个字符")
    private String logoImageId;

    /** 企业级折扣率 [0,1]，为空则不修改/默认 1 */
    @DecimalMin(value = "0", message = "企业折扣率不能小于 0")
    @DecimalMax(value = "1", message = "企业折扣率不能大于 1")
    private BigDecimal priceRatio;
}
