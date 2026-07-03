package com.rsdp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * 当前登录用户上下文工具。
 *
 * <p>封装 Spring Security {@link SecurityContextHolder}，方便 Service 层获取当前操作人。</p>
 */
public final class SecurityOperatorContext {

    private SecurityOperatorContext() {
    }

    /**
     * 获取当前登录用户名。
     *
     * @return 用户名；未登录返回 "anonymous"
     */
    public static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return principal.toString();
    }

    /**
     * 判断当前是否已认证。
     */
    public static boolean isAuthenticated() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }
}
