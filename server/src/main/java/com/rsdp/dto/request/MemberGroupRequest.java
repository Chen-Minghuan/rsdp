package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 企业分组创建/更新请求。
 */
@Data
public class MemberGroupRequest {

    @NotBlank(message = "分组名称不能为空")
    @Size(max = 64, message = "分组名称不能超过 64 个字符")
    private String groupName;

    /** 启用/停用；为空则不修改（创建时默认启用） */
    private Boolean enabled;
}
