package com.rsdp.service;

import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EffectivePriceRateResolver} 单元测试（企业折扣率优先 / 全局回退）。
 */
@ExtendWith(MockitoExtension.class)
class EffectivePriceRateResolverTest {

    @Mock
    private CompanyService companyService;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private EffectivePriceRateResolver resolver;

    @Test
    void companyPriceRatioShouldTakePrecedence() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(companyService.resolveOrderPriceRate("user-1")).thenReturn(new BigDecimal("0.9"));

            assertThat(resolver.resolve()).isEqualByComparingTo("0.9");
            verify(configService, never()).getOrderPriceRate();
        }
    }

    @Test
    void shouldFallbackToGlobalRateWhenNoCompany() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(companyService.resolveOrderPriceRate("user-1")).thenReturn(null);
            when(configService.getOrderPriceRate()).thenReturn(new BigDecimal("0.8"));

            assertThat(resolver.resolve()).isEqualByComparingTo("0.8");
        }
    }
}
