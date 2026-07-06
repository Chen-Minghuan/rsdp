package com.rsdp.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * JWT 工具类。
 */
@Slf4j
@Component
public class JwtUtil {

    @Value("${rsdp.jwt.secret:}")
    private String secret;

    @Value("${rsdp.jwt.expiration-hours:24}")
    private long expirationHours;

    private static final String CLAIM_USER_ID = "userId";
    private static final String CLAIM_NICKNAME = "nickname";
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_PERMISSIONS = "permissions";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";

    private SecretKey getSigningKey() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT 密钥未配置，请设置 rsdp.jwt.secret");
        }
        byte[] keyBytes = Decoders.BASE64.decode(secret);
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成 JWT。
     *
     * @param userId       用户 ID
     * @param username     用户名
     * @param nickname     昵称
     * @param role         角色
     * @param permissions  权限列表
     * @param tokenVersion 用户 token 版本号
     * @return token
     */
    public String generateToken(String userId, String username, String nickname, String role, List<String> permissions, int tokenVersion) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationHours * 60 * 60 * 1000);
        return Jwts.builder()
            .subject(username)
            .claim(CLAIM_USER_ID, userId)
            .claim(CLAIM_NICKNAME, nickname)
            .claim(CLAIM_ROLE, role)
            .claim(CLAIM_PERMISSIONS, permissions)
            .claim(CLAIM_TOKEN_VERSION, tokenVersion)
            .issuedAt(now)
            .expiration(expiry)
            .signWith(getSigningKey())
            .compact();
    }

    /**
     * 解析并验证 JWT。
     *
     * @param token token
     * @return Claims
     */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("JWT 解析失败: {}", e.getMessage());
            return null;
        }
    }

    public String getUserId(Claims claims) {
        return claims.get(CLAIM_USER_ID, String.class);
    }

    public String getNickname(Claims claims) {
        return claims.get(CLAIM_NICKNAME, String.class);
    }

    public String getRole(Claims claims) {
        return claims.get(CLAIM_ROLE, String.class);
    }

    public Integer getTokenVersion(Claims claims) {
        return claims.get(CLAIM_TOKEN_VERSION, Integer.class);
    }

    @SuppressWarnings("unchecked")
    public List<String> getPermissions(Claims claims) {
        Object value = claims.get(CLAIM_PERMISSIONS);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .collect(Collectors.toList());
        }
        return List.of();
    }
}
