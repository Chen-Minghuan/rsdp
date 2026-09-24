package com.rsdp.security;

import com.rsdp.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PublicFloorPlanTokenService} 单元测试。
 */
class PublicFloorPlanTokenServiceTest {

    private PublicFloorPlanTokenService service;

    @BeforeEach
    void setUp() {
        service = new PublicFloorPlanTokenService();
        String secret = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
        ReflectionTestUtils.setField(service, "secret", secret);
        ReflectionTestUtils.setField(service, "expirationHours", 24L);
    }

    @Test
    void generateAndResolve_shouldRoundTripAnalysisId() {
        String token = service.generate("FPA-PUBLIC-1");

        assertThat(service.resolveAnalysisId(token)).isEqualTo("FPA-PUBLIC-1");
    }

    @Test
    void resolveAnalysisId_shouldRejectInvalidToken() {
        assertThatThrownBy(() -> service.resolveAnalysisId("invalid-token"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("访问凭证已失效");
    }

    @Test
    void resolveAnalysisId_shouldRejectExpiredToken() {
        ReflectionTestUtils.setField(service, "expirationHours", -1L);
        String token = service.generate("FPA-PUBLIC-1");

        assertThatThrownBy(() -> service.resolveAnalysisId(token))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("访问凭证已失效");
    }
}
