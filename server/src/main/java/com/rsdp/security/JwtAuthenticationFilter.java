package com.rsdp.security;

import com.rsdp.entity.SysUser;
import com.rsdp.mapper.SysUserMapper;
import com.rsdp.security.datascope.DataScopeContext;
import com.rsdp.util.JwtUtil;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * JWT 认证过滤器。
 *
 * <p>从请求头 {@code Authorization: Bearer <token>} 解析 JWT，
 * 并将认证信息写入 Spring Security 上下文。</p>
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final SysUserMapper sysUserMapper;
    private final DataScopeContext dataScopeContext;

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Value("${rsdp.jwt.cookie-name:rsdp_token}")
    private String cookieName;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            String token = resolveToken(request);
            if (token != null) {
                Claims claims = jwtUtil.parseToken(token);
                if (claims != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                    String username = claims.getSubject();

                    // 校验用户是否存在且未被禁用；若校验失败则仅清空上下文并放行，
                    // 由后续 Spring Security 根据请求路径决定是否拒绝，避免旧 token 阻塞 login 等 permitAll 接口。
                    SysUser user = sysUserMapper.selectByUsername(username);
                    if (user == null || !"active".equals(user.getStatus())) {
                        SecurityContextHolder.clearContext();
                    } else {
                        // 校验 token 版本号，防止角色/权限变更后旧 token 继续生效
                        Integer tokenVersion = jwtUtil.getTokenVersion(claims);
                        Integer currentVersion = user.getTokenVersion();
                        if (tokenVersion == null || !tokenVersion.equals(currentVersion == null ? 0 : currentVersion)) {
                            SecurityContextHolder.clearContext();
                        } else {
                            String role = jwtUtil.getRole(claims);
                            List<String> permissions = jwtUtil.getPermissions(claims);

                            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                            if (role != null) {
                                authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                            }
                            permissions.forEach(perm -> authorities.add(new SimpleGrantedAuthority(perm)));

                            SecurityUser securityUser = new SecurityUser(
                                user.getUserId(), username, user.getPasswordHash(), authorities);
                            UsernamePasswordAuthenticationToken authentication =
                                new UsernamePasswordAuthenticationToken(securityUser, null, authorities);
                            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                            SecurityContextHolder.getContext().setAuthentication(authentication);
                        }
                    }
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            // 清理数据范围缓存，避免线程复用导致旧请求数据泄漏
            dataScopeContext.clearCache();
        }
    }

    private String resolveToken(HttpServletRequest request) {
        String bearerToken = request.getHeader(AUTHORIZATION_HEADER);
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith(BEARER_PREFIX)) {
            return bearerToken.substring(BEARER_PREFIX.length());
        }

        // 兼容 HttpOnly Cookie 方案：前端不再在 localStorage 保存 token
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
