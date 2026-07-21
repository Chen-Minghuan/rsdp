package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 登录响应。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;
    private String tokenType;
    private String userId;
    private String username;
    private String nickname;
    private String role;
    private List<String> roles;
    private List<String> permissions;
    private Boolean viewFullCatalog;
    private List<String> factoryCodes;
    /** 永久邀请码（邀请链接 ?inviteCode= 使用） */
    private String inviteCode;
    /** 认证设计师标记 */
    private Boolean certifiedDesigner;
    /** 所属企业 ID（非空即企业账号） */
    private String companyId;
}
