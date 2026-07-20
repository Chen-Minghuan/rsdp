package com.rsdp.controller;

import com.rsdp.common.PageResult;
import com.rsdp.common.Result;
import com.rsdp.dto.request.UserCreateRequest;
import com.rsdp.dto.request.UserResetPasswordRequest;
import com.rsdp.dto.request.UserUpdateRequest;
import com.rsdp.dto.response.UserResponse;
import com.rsdp.security.Permissions;
import com.rsdp.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户管理接口（管理员）。
 */
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;

    /**
     * 用户列表。
     */
    @GetMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public Result<PageResult<UserResponse>> listUsers(
        @RequestParam(defaultValue = "1") int page,
        @RequestParam(defaultValue = "20") @Max(500) int size,
        @RequestParam(required = false) String keyword
    ) {
        return Result.ok(PageResult.from(userService.listUsers(page, size, keyword)));
    }

    /**
     * 创建用户。
     */
    @PostMapping
    @PreAuthorize("hasAuthority('" + Permissions.USER_CREATE + "')")
    public Result<UserResponse> createUser(@Valid @RequestBody UserCreateRequest request) {
        return Result.ok(userService.createUser(request));
    }

    /**
     * 编辑用户。
     */
    @PutMapping("/{userId}")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    public Result<UserResponse> updateUser(
        @PathVariable @NotBlank String userId,
        @Valid @RequestBody UserUpdateRequest request
    ) {
        return Result.ok(userService.updateUser(userId, request));
    }

    /**
     * 重置密码。
     */
    @PutMapping("/{userId}/reset-password")
    @PreAuthorize("hasAuthority('" + Permissions.USER_RESET_PASSWORD + "')")
    public Result<Void> resetPassword(
        @PathVariable @NotBlank String userId,
        @Valid @RequestBody UserResetPasswordRequest request
    ) {
        userService.resetPassword(userId, request.getNewPassword());
        return Result.ok();
    }

    /**
     * 切换用户状态（启用/禁用）。
     */
    @PutMapping("/{userId}/status")
    @PreAuthorize("hasAuthority('" + Permissions.USER_UPDATE + "')")
    public Result<UserResponse> updateStatus(
        @PathVariable @NotBlank String userId,
        @RequestParam @Pattern(regexp = "active|disabled", message = "状态只能是 active 或 disabled") String status
    ) {
        return Result.ok(userService.updateStatus(userId, status));
    }
}
