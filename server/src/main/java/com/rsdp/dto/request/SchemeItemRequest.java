package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 搭配方案项请求。
 */
@Data
public class SchemeItemRequest {

    /** RSPU ID。 */
    @NotBlank(message = "RSPU ID 不能为空")
    private String rspuId;

    /** 选中的 RSKU ID。 */
    @NotBlank(message = "RSKU ID 不能为空")
    private String rskuId;

    /** 排序号。 */
    private Integer sortOrder;
}
