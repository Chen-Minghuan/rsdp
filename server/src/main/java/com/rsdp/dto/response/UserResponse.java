package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户响应。
 */
@Data
public class UserResponse {

    private String userId;

    private String username;

    private String nickname;

    private String roleCode;

    private String roleName;

    private String status;

    private List<String> factoryCodes;

    private LocalDateTime lastLoginAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
