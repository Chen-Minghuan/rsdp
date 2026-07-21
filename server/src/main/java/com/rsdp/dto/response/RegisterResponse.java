package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 注册响应。
 */
@Data
@AllArgsConstructor
public class RegisterResponse {

    private String userId;
    private String username;
    private String nickname;
    /** 新用户自己的永久邀请码 */
    private String inviteCode;
}
