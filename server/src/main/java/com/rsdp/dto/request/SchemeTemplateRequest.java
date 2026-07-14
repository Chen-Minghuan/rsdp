package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 设置/取消方案模板请求。
 */
@Data
public class SchemeTemplateRequest {

    @NotNull(message = "isTemplate 不能为空")
    private Boolean isTemplate;

    /** 模板标签（设为模板时可选） */
    private List<String> templateTags;
}
