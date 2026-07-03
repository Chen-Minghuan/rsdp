package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.service.AuthService;
import org.springframework.util.StringUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final AuthService authService;

    /**
     * 用户登录。
     *
     * @param request 登录请求
     * @return JWT 令牌与用户信息
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.ok(authService.login(request));
    }

    /**
     * 获取当前登录用户信息。
     *
     * @param username 当前认证用户名
     * @return 用户信息
     */
    @GetMapping("/me")
    public Result<LoginResponse> me(@AuthenticationPrincipal String username) {
        if (!StringUtils.hasText(username)) {
            throw new BusinessException("用户未登录");
        }
        return Result.ok(authService.getCurrentUser(username));
    }
}
