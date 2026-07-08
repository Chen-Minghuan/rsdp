package com.rsdp.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Optional;

import java.util.Collection;
import java.util.Collections;

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
        Authentication authentication = getAuthentication();
        if (authentication == null) {
            return "anonymous";
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserDetails userDetails) {
            return userDetails.getUsername();
        }
        return principal.toString();
    }

    /**
     * 获取当前登录用户 ID。
     *
     * @return {@code sys_user.user_id}；无法获取时返回 {@code null}
     */
    public static String currentUserId() {
        return Optional.ofNullable(getAuthentication())
            .map(Authentication::getPrincipal)
            .filter(principal -> principal instanceof SecurityUser)
            .map(principal -> ((SecurityUser) principal).getUserId())
            .orElse(null);
    }

    /**
     * 判断当前是否已认证。
     */
    public static boolean isAuthenticated() {
        Authentication authentication = getAuthentication();
        return authentication != null && authentication.isAuthenticated();
    }

    /**
     * 判断当前用户是否为 ADMIN。
     *
     * @return 是否拥有 ROLE_ADMIN
     */
    public static boolean isCurrentUserAdmin() {
        return hasAuthority("ROLE_ADMIN");
    }

    /**
     * 判断当前用户是否为 EDITOR。
     *
     * @return 是否拥有 ROLE_EDITOR
     */
    public static boolean isCurrentUserEditor() {
        return hasAuthority("ROLE_EDITOR");
    }

    /**
     * 判断当前用户是否为平台运营人员（ADMIN 或 EDITOR）。
     *
     * <p>平台运营人员拥有全库数据视图，可协助管理员处理产品/工厂/方案等业务数据，
     * 但不具备用户管理、系统配置等高级管理员权限。</p>
     *
     * @return 是否拥有 ROLE_ADMIN 或 ROLE_EDITOR
     */
    public static boolean isPlatformStaff() {
        return isCurrentUserAdmin() || isCurrentUserEditor();
    }

    /**
     * 判断当前用户是否为 DESIGNER。
     *
     * @return 是否拥有 ROLE_DESIGNER
     */
    public static boolean isCurrentUserDesigner() {
        return hasAuthority("ROLE_DESIGNER");
    }

    /**
     * 判断当前用户是否为 FACTORY_ADMIN。
     *
     * @return 是否拥有 ROLE_FACTORY_ADMIN
     */
    public static boolean isCurrentUserFactoryAdmin() {
        return hasAuthority("ROLE_FACTORY_ADMIN");
    }

    /**
     * 判断当前用户是否拥有指定权限或角色。
     *
     * @param authority 权限字符串（如 {@code product:read}）或角色字符串（如 {@code ROLE_ADMIN}）
     * @return 是否拥有
     */
    public static boolean hasAuthority(String authority) {
        Authentication authentication = getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch(authority::equals);
    }

    private static Authentication getAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
            || "anonymousUser".equals(authentication.getPrincipal())) {
            return null;
        }
        return authentication;
    }

    /**
     * 获取当前用户权限列表。
     *
     * @return 权限集合；未登录返回空集合
     */
    public static Collection<? extends GrantedAuthority> currentAuthorities() {
        Authentication authentication = getAuthentication();
        return authentication == null ? Collections.emptyList() : authentication.getAuthorities();
    }
}
