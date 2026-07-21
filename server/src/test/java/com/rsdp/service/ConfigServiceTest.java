package com.rsdp.service;

import com.rsdp.entity.SysConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysConfigMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * {@link ConfigService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class ConfigServiceTest {

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private AuditLogService auditLogService;

    @InjectMocks
    private ConfigService configService;

    @Test
    void getOrderPriceRateShouldDefaultToOneWhenMissing() {
        when(sysConfigMapper.selectById(ConfigService.ORDER_PRICE_RATE_KEY)).thenReturn(null);

        assertThat(configService.getOrderPriceRate()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void getOrderPriceRateShouldParseConfiguredValue() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.ORDER_PRICE_RATE_KEY);
        config.setConfigValue("0.85");
        when(sysConfigMapper.selectById(ConfigService.ORDER_PRICE_RATE_KEY)).thenReturn(config);

        assertThat(configService.getOrderPriceRate()).isEqualByComparingTo(new BigDecimal("0.85"));
    }

    @Test
    void getOrderPriceRateShouldRejectMalformedValue() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.ORDER_PRICE_RATE_KEY);
        config.setConfigValue("abc");
        when(sysConfigMapper.selectById(ConfigService.ORDER_PRICE_RATE_KEY)).thenReturn(config);

        assertThatThrownBy(() -> configService.getOrderPriceRate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("格式错误");
    }

    @Test
    void getOrderPriceRateShouldRejectOutOfRangeValue() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.ORDER_PRICE_RATE_KEY);
        config.setConfigValue("1.5");
        when(sysConfigMapper.selectById(ConfigService.ORDER_PRICE_RATE_KEY)).thenReturn(config);

        assertThatThrownBy(() -> configService.getOrderPriceRate())
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("0 到 1");
    }

    @Test
    void setOrderPriceRateShouldRejectOutOfRangeValue() {
        assertThatThrownBy(() -> configService.set(ConfigService.ORDER_PRICE_RATE_KEY, "-0.1"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("0 到 1");
    }
}
