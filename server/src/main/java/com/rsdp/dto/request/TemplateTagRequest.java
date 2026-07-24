package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 模板标签创建/更新请求。
 */
@Data
public class TemplateTagRequest {

    @NotBlank(message = "标签名称不能为空")
    @Size(max = 64, message = "标签名称不能超过 64 个字符")
    private String tagName;

    /** 排序值（越小越靠前）；为空则不修改/默认 0 */
    private Integer sortOrder;

    /** 启用/停用；为空则不修改（创建时默认启用） */
    private Boolean enabled;
}
