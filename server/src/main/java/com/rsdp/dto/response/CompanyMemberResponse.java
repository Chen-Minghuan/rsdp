package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 企业成员响应。
 */
@Data
public class CompanyMemberResponse {

    private String userId;
    private String username;
    private String nickname;
    private String groupId;
    private String groupName;
    private String status;
    private String roleCode;
    private Boolean certifiedDesigner;
    /** 是否企业管理员 */
    private Boolean owner;
    private LocalDateTime createdAt;
}
