package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 套用模板创建方案请求。
 */
@Data
public class CopyFromTemplateRequest {

    /** 新方案归属的设计项目 ID */
    @NotBlank(message = "项目 ID 不能为空")
    private String projectId;

    /** 新方案名称（可选，默认模板名 + 后缀） */
    private String schemeName;
}
