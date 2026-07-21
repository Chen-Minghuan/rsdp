package com.rsdp.dto.request;

import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 成员分组调整请求。groupId 为空表示移出分组（未分组）。
 */
@Data
public class MemberGroupAssignRequest {

    @Size(max = 64, message = "分组 ID 不能超过 64 个字符")
    private String groupId;
}
