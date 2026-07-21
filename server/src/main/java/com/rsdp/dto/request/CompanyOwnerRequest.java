package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 企业管理员变更请求。
 */
@Data
public class CompanyOwnerRequest {

    @NotBlank(message = "新管理员用户 ID 不能为空")
    @Size(max = 64, message = "用户 ID 不能超过 64 个字符")
    private String newOwnerId;
}
