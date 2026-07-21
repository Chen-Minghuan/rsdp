package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业分组响应。
 */
@Data
public class MemberGroupResponse {

    private String groupId;
    private String companyId;
    private String groupName;
    private Boolean enabled;
    private Integer memberCount;
    private LocalDateTime createdAt;
}
