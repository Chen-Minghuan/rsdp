package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 官网自定义字典创建/更新请求。
 */
@Data
public class PlatformCustomDictRequest {

    @NotBlank(message = "字典名称不能为空")
    @Size(max = 64)
    private String dictName;

    @NotBlank(message = "字典类型不能为空")
    @Size(max = 32)
    private String dictType;

    @Size(max = 16)
    private String status;
}
