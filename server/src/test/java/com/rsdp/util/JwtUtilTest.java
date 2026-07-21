package com.rsdp.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtUtil} 单元测试。
 */
class JwtUtilTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        ReflectionTestUtils.setField(jwtUtil, "secret", Base64.getEncoder().encodeToString(keyBytes));
        ReflectionTestUtils.setField(jwtUtil, "expirationHours", 24L);
    }

    @Test
    void generateAndParseToken_shouldWork() {
        String token = jwtUtil.generateToken("USER-001", "admin", "管理员", "ADMIN", List.of("user:read", "user:create"), 3);
        assertThat(token).isNotBlank();

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(jwtUtil.getUserId(claims)).isEqualTo("USER-001");
        assertThat(jwtUtil.getNickname(claims)).isEqualTo("管理员");
        assertThat(jwtUtil.getRole(claims)).isEqualTo("ADMIN");
        assertThat(jwtUtil.getPermissions(claims)).containsExactly("user:read", "user:create");
        assertThat(jwtUtil.getTokenVersion(claims)).isEqualTo(3);
    }

    @Test
    void parseToken_invalidToken_shouldReturnNull() {
        Claims claims = jwtUtil.parseToken("invalid-token");
        assertThat(claims).isNull();
    }
}
