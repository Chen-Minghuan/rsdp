package com.rsdp.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;

/**
 * 扩展 Spring Security 的 {@link User}，在认证 principal 中携带用户 ID。
 *
 * <p>Service 层可通过 {@link SecurityOperatorContext#currentUserId()} 获取 {@code sys_user.user_id}，
 * 用于写入带有 {@code REFERENCES sys_user(user_id)} 外键的 {@code created_by} 字段。</p>
 */
public class SecurityUser extends User {

    private final String userId;

    public SecurityUser(String userId, String username, String password,
                        Collection<? extends GrantedAuthority> authorities) {
        super(username, password, authorities);
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }
}
