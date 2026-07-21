package com.rsdp.controller;

import com.rsdp.common.Result;
import com.rsdp.dto.request.LoginRequest;
import com.rsdp.dto.request.RegisterRequest;
import com.rsdp.dto.request.UserPreferenceUpdateRequest;
import com.rsdp.dto.response.LoginResponse;
import com.rsdp.dto.response.RegisterResponse;
import com.rsdp.dto.response.UserResponse;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.security.SecurityUser;
import com.rsdp.service.AuthService;
import com.rsdp.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final UserService userService;

    @Value("${rsdp.security.login.trusted-proxy-count:0}")
    private int trustedProxyCount;

    @Value("${rsdp.jwt.cookie-name:rsdp_token}")
    private String cookieName;

    @Value("${rsdp.jwt.cookie-secure:true}")
    private boolean cookieSecure;

    @Value("${rsdp.jwt.cookie-same-site:Strict}")
    private String cookieSameSite;

    @Value("${rsdp.jwt.expiration-hours:24}")
    private long expirationHours;

    /**
     * 用户登录。
     *
     * <p>登录成功后通过 {@code Set-Cookie} 返回 HttpOnly JWT Cookie，前端不再需要在 localStorage 中保存 token。</p>
     *
     * @param request  登录请求
     * @param response HTTP 响应，用于写入 Cookie
     * @return 用户信息（不含 token）
     */
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest httpRequest,
                                       HttpServletResponse response) {
        String ip = extractClientIp(httpRequest);
        LoginResponse loginResponse = authService.login(request, ip);

        ResponseCookie cookie = ResponseCookie.from(cookieName, loginResponse.getToken())
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(expirationHours * 60 * 60)
            .build();
        response.addHeader("Set-Cookie", cookie.toString());

        // 返回体中不再携带 token，避免泄露
        LoginResponse responseBody = new LoginResponse(
            null,
            loginResponse.getTokenType(),
            loginResponse.getUserId(),
            loginResponse.getUsername(),
            loginResponse.getNickname(),
            loginResponse.getRole(),
            loginResponse.getRoles(),
            loginResponse.getPermissions(),
            loginResponse.getViewFullCatalog(),
            loginResponse.getFactoryCodes(),
            loginResponse.getInviteCode(),
            loginResponse.getCertifiedDesigner(),
            loginResponse.getCompanyId()
        );
        return Result.ok(responseBody);
    }

    /**
     * 公开注册（可携带邀请码归因）。
     *
     * @param request 注册请求
     * @return 注册响应
     */
    @PostMapping("/register")
    public Result<RegisterResponse> register(@Valid @RequestBody RegisterRequest request) {
        return Result.ok(authService.register(request));
    }

    /**
     * 用户登出。
     *
     * <p>清除 HttpOnly JWT Cookie。</p>
     *
     * @param response HTTP 响应
     * @return 空结果
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from(cookieName, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(0)
            .build();
        response.addHeader("Set-Cookie", cookie.toString());
        return Result.ok();
    }

    /**
     * 获取当前登录用户信息。
     *
     * @param username 当前认证用户名
     * @return 用户信息
     */
    @GetMapping("/me")
    public Result<LoginResponse> me(@AuthenticationPrincipal SecurityUser securityUser) {
        if (securityUser == null || !StringUtils.hasText(securityUser.getUsername())) {
            throw new BusinessException("用户未登录");
        }
        return Result.ok(authService.getCurrentUser(securityUser.getUsername()));
    }

    /**
     * 更新当前登录用户偏好设置。
     *
     * <p>当前仅支持修改「显示全产品库（去重）」开关。</p>
     *
     * @param request 偏好更新请求
     * @return 更新后的用户信息
     */
    @PutMapping("/me/preferences")
    public Result<UserResponse> updateMyPreferences(@Valid @RequestBody UserPreferenceUpdateRequest request) {
        String userId = SecurityOperatorContext.currentUserId();
        if (!StringUtils.hasText(userId)) {
            throw new BusinessException("用户未登录");
        }
        return Result.ok(userService.updateMyPreferences(userId, request));
    }

    private String extractClientIp(HttpServletRequest request) {
        // 默认不信任任何代理，防止客户端伪造 X-Forwarded-For。
        // 若部署在可信反向代理后，可通过 rsdp.security.login.trusted-proxy-count 配置代理层数。
        String xff = request.getHeader("X-Forwarded-For");
        if (trustedProxyCount > 0 && StringUtils.hasText(xff)) {
            String[] parts = xff.split(",");
            int index = parts.length - trustedProxyCount;
            if (index >= 0) {
                return parts[index].trim();
            }
        }
        return request.getRemoteAddr();
    }
}
