package com.rsdp.util;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link JwtUtil} 单元测试。
 */
class JwtUtilTest {

    private final JwtUtil jwtUtil = new JwtUtil();

    JwtUtilTest() {
        ReflectionTestUtils.setField(jwtUtil, "secret", "g3g6Ryj6ty4Dsw0jm1ImR59dbRAOI98q3qKVf7gz0jU=");
        ReflectionTestUtils.setField(jwtUtil, "expirationHours", 24L);
    }

    @Test
    void generateAndParseToken_shouldWork() {
        String token = jwtUtil.generateToken("USER-001", "admin", "管理员", "ADMIN", List.of("user:read", "user:create"));
        assertThat(token).isNotBlank();

        Claims claims = jwtUtil.parseToken(token);
        assertThat(claims).isNotNull();
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(jwtUtil.getUserId(claims)).isEqualTo("USER-001");
        assertThat(jwtUtil.getNickname(claims)).isEqualTo("管理员");
        assertThat(jwtUtil.getRole(claims)).isEqualTo("ADMIN");
        assertThat(jwtUtil.getPermissions(claims)).containsExactly("user:read", "user:create");
    }

    @Test
    void parseToken_invalidToken_shouldReturnNull() {
        Claims claims = jwtUtil.parseToken("invalid-token");
        assertThat(claims).isNull();
    }
}
