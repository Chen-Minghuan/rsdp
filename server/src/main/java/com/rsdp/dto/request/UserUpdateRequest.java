package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 用户编辑请求。
 */
@Data
public class UserUpdateRequest {

    @Size(max = 64, message = "昵称长度不能超过 64")
    private String nickname;

    @NotBlank(message = "角色不能为空")
    private String roleCode;

    private List<String> factoryCodes;

    private String status;
}
