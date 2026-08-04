package com.rsdp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.Result;
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
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
    private final ObjectMapper objectMapper;

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
                if (claims == null) {
                    if (rejectInvalidToken(request, response, "登录已过期或无效，请重新登录")) {
                        return;
                    }
                } else if (SecurityContextHolder.getContext().getAuthentication() == null) {
                    String username = claims.getSubject();
                    SysUser user = sysUserMapper.selectByUsername(username);
                    if (user == null) {
                        if (rejectInvalidToken(request, response, "登录已失效，请重新登录")) {
                            return;
                        }
                    } else if (!"active".equals(user.getStatus())) {
                        if (rejectInvalidToken(request, response, "账户已被禁用")) {
                            return;
                        }
                    } else {
                        // 校验 token 版本号，防止角色/权限/密码变更后旧 token 继续生效
                        Integer tokenVersion = jwtUtil.getTokenVersion(claims);
                        Integer currentVersion = user.getTokenVersion();
                        if (tokenVersion == null || !tokenVersion.equals(currentVersion == null ? 0 : currentVersion)) {
                            if (rejectInvalidToken(request, response, "登录已失效，请重新登录")) {
                                return;
                            }
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

    /**
     * 对携带了 token 但校验失败的请求，若非公开接口则直接返回 401 并提示重新登录。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param message  提示信息
     * @return true 表示已直接响应，调用方应结束过滤器链；false 表示公开接口，继续放行
     * @throws IOException 响应写入失败
     */
    private boolean rejectInvalidToken(HttpServletRequest request, HttpServletResponse response,
                                       String message) throws IOException {
        SecurityContextHolder.clearContext();
        if (isPublicPath(request)) {
            return false;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error(401, message));
        return true;
    }

    private boolean isPublicPath(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/v1/auth/login")
            || path.startsWith("/api/v1/auth/register")
            || path.startsWith("/api/v1/public/")
            || path.startsWith("/api/v1/images/");
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
