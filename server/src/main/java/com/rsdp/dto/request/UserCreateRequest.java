package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户创建请求。
 */
@Data
public class UserCreateRequest {

    @NotBlank(message = "用户名不能为空")
    @Size(max = 32, message = "用户名长度不能超过 32")
    private String username;

    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 64, message = "密码长度需在 6-64 之间")
    private String password;

    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @NotBlank(message = "角色不能为空")
    private String roleCode;

    private Boolean viewFullCatalog;

    private List<String> factoryCodes;
}
