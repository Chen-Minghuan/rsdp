package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 字典项启停用请求。
 */
@Data
public class DictStatusRequest {

    @NotBlank(message = "状态不能为空")
    @Pattern(regexp = "active|disabled", message = "状态只能是 active 或 disabled")
    private String status;
}
