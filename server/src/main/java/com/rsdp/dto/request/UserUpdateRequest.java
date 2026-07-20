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

    @Size(max = 128, message = "企业名称长度不能超过 128")
    private String companyName;

    @Size(max = 64, message = "团队分组长度不能超过 64")
    private String groupName;

    @NotBlank(message = "角色不能为空")
    private String roleCode;

    private Boolean viewFullCatalog;

    private List<String> factoryCodes;
}
