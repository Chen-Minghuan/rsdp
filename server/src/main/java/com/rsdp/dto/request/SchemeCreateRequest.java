package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 创建搭配方案请求。
 */
@Data
public class SchemeCreateRequest {

    /** 方案名称。 */
    @NotBlank(message = "方案名称不能为空")
    private String schemeName;

    /** 空间类型（可选）。 */
    private String roomType;

    /** 预算上限（可选）。 */
    private BigDecimal budgetLimit;

    /** 方案项列表。 */
    @NotEmpty(message = "请至少选择一个产品")
    @Valid
    private List<SchemeItemRequest> items;
}
