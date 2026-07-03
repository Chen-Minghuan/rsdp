package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 字典项创建请求。
 *
 * <p>当前仅开放 {@code material} 与 {@code scene} 两类字典的扩展，
 * 避免用户随意修改核心受控字典（如 category、factory_level 等）。</p>
 */
@Data
public class DictCreateRequest {

    @NotBlank(message = "字典类型不能为空")
    @Size(max = 32, message = "字典类型长度不能超过 32")
    private String dictType;

    @NotBlank(message = "字典编码不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9]+$", message = "字典编码只能包含字母和数字")
    @Size(max = 32, message = "字典编码长度不能超过 32")
    private String dictCode;

    @NotBlank(message = "字典名称不能为空")
    @Size(max = 64, message = "字典名称长度不能超过 64")
    private String dictName;

    @Size(max = 64, message = "英文名称长度不能超过 64")
    private String dictNameEn;
}
