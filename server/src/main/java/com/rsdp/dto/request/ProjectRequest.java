package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 设计项目创建/更新请求。
 */
@Data
public class ProjectRequest {

    @NotBlank(message = "项目名称不能为空")
    @Size(max = 128, message = "项目名称不能超过 128 个字符")
    private String projectName;

    @Size(max = 32, message = "项目类型不能超过 32 个字符")
    private String projectType;

    @Size(max = 128, message = "企业名称不能超过 128 个字符")
    private String companyName;

    @Size(max = 512, message = "备注不能超过 512 个字符")
    private String remark;
}
