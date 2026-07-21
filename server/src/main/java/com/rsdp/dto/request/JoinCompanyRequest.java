package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 邀请用户加入企业请求。
 */
@Data
public class JoinCompanyRequest {

    @NotBlank(message = "用户 ID 不能为空")
    @Size(max = 64, message = "用户 ID 不能超过 64 个字符")
    private String userId;

    /** 初始分组（可选） */
    @Size(max = 64, message = "分组 ID 不能超过 64 个字符")
    private String groupId;
}
