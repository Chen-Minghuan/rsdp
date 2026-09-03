package com.rsdp.service;

import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RskuSupply;
import com.rsdp.entity.PricingRule;
import com.rsdp.entity.SysConfig;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * {@link PricingService} 单元测试（串真实 {@link ConfigService} 验证配置缺省/非法行为）。
 */
@ExtendWith(MockitoExtension.class)
class PricingServiceTest {

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PricingRuleService pricingRuleService;

    private PricingService pricingService;

    @BeforeEach
    void setUp() {
        pricingService = new PricingService(new ConfigService(sysConfigMapper, auditLogService), pricingRuleService);
    }

    private RspuMaster rspuWithRetailPrice(String retailPrice) {
        RspuMaster rspu = new RspuMaster();
        rspu.setRspuId("RSPU-001");
        if (retailPrice != null) {
            rspu.setRetailPrice(new BigDecimal(retailPrice));
        }
        return rspu;
    }

    private RskuSupply rskuWithFactoryPrice(String factoryPrice) {
        RskuSupply rsku = new RskuSupply();
        rsku.setRskuId("RSKU-001");
        if (factoryPrice != null) {
            rsku.setFactoryPrice(new BigDecimal(factoryPrice));
        }
        return rsku;
    }

    @Test
    void resolveSalePriceShouldPreferRetailPrice() {
        // RSPU 已录入建议销售价：直接返回（保留原值精度），不查加价倍率配置
        BigDecimal salePrice = pricingService.resolveSalePrice(
            rspuWithRetailPrice("1999.9"), rskuWithFactoryPrice("800.00"));

        assertThat(salePrice).isEqualByComparingTo("1999.9");
    }

    @Test
    void resolveSalePriceShouldComputeCostTimesDefaultMarkup() {
        // 无建议销售价、无品类规则：成本 1000 × 缺省全局倍率 2.5 = 2500.00
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(null);

        BigDecimal salePrice = pricingService.resolveSalePrice(
            rspuWithRetailPrice(null), rskuWithFactoryPrice("1000.00"));

        assertThat(salePrice).isEqualByComparingTo("2500.00");
    }

    @Test
    void resolveSalePriceShouldUseConfiguredMarkup() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.MARKUP_GLOBAL_KEY);
        config.setConfigValue("3.0");
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(config);

        BigDecimal salePrice = pricingService.resolveSalePrice(
            null, rskuWithFactoryPrice("333.33"));

        // 333.33 × 3.0 = 999.99（HALF_UP 保留两位）
        assertThat(salePrice).isEqualByComparingTo("999.99");
    }

    @Test
    void resolveSalePriceShouldReturnNullWhenUnpriced() {
        assertThat(pricingService.resolveSalePrice(rspuWithRetailPrice(null), rskuWithFactoryPrice(null)))
            .isNull();
        assertThat(pricingService.resolveSalePrice(null, null)).isNull();
    }

    @Test
    void resolveSalePriceShouldThrowOnMalformedMarkupConfig() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.MARKUP_GLOBAL_KEY);
        config.setConfigValue("abc");
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(config);

        assertThatThrownBy(() -> pricingService.resolveSalePrice(null, rskuWithFactoryPrice("100.00")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("格式错误");
    }

    @Test
    void resolveSalePriceShouldThrowOnNonPositiveMarkupConfig() {
        SysConfig config = new SysConfig();
        config.setConfigKey(ConfigService.MARKUP_GLOBAL_KEY);
        config.setConfigValue("0");
        lenient().when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(config);

        assertThatThrownBy(() -> pricingService.resolveSalePrice(null, rskuWithFactoryPrice("100.00")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("大于 0");
    }

    @Test
    void resolveSalePriceDetailShouldHitCategoryRule() {
        // 品类 FS 命中规则 3.0：成本 1000 × 3.0 = 3000.00，来源 CATEGORY_RULE
        when(pricingRuleService.listAllRules()).thenReturn(Map.of("FS", rule("FS", "3.0")));

        RspuMaster rspu = rspuWithRetailPrice(null);
        rspu.setCategoryCode("FS");
        PricingService.SalePriceDetail detail = pricingService.resolveSalePriceDetail(
            rspu, rskuWithFactoryPrice("1000.00"));

        assertThat(detail.salePrice()).isEqualByComparingTo("3000.00");
        assertThat(detail.source()).isEqualTo(PricingService.SOURCE_CATEGORY_RULE);
        assertThat(detail.appliedMultiplier()).isEqualByComparingTo("3.0");
        assertThat(detail.cost()).isEqualByComparingTo("1000.00");
    }

    @Test
    void resolveSalePriceDetailShouldFallbackToGlobalWhenCategoryRuleMissing() {
        // 品类无规则：回退全局倍率，来源 GLOBAL
        when(pricingRuleService.listAllRules()).thenReturn(Map.of("CH", rule("CH", "4.0")));
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(null);

        RspuMaster rspu = rspuWithRetailPrice(null);
        rspu.setCategoryCode("FS");
        PricingService.SalePriceDetail detail = pricingService.resolveSalePriceDetail(
            rspu, rskuWithFactoryPrice("1000.00"));

        assertThat(detail.salePrice()).isEqualByComparingTo("2500.00");
        assertThat(detail.source()).isEqualTo(PricingService.SOURCE_GLOBAL);
        assertThat(detail.appliedMultiplier()).isEqualByComparingTo("2.5");
    }

    @Test
    void resolveSalePriceDetailShouldPreferRetailPriceOverCategoryRule() {
        // retail_price 仍最高优先：即使品类有规则也走 MANUAL
        RspuMaster rspu = rspuWithRetailPrice("1999.9");
        rspu.setCategoryCode("FS");

        PricingService.SalePriceDetail detail = pricingService.resolveSalePriceDetail(
            rspu, rskuWithFactoryPrice("800.00"));

        assertThat(detail.salePrice()).isEqualByComparingTo("1999.9");
        assertThat(detail.source()).isEqualTo(PricingService.SOURCE_MANUAL);
        assertThat(detail.appliedMultiplier()).isNull();
        assertThat(detail.cost()).isEqualByComparingTo("800.00");
    }

    @Test
    void resolveSalePriceDetailShouldReportNoneWhenUnpriced() {
        PricingService.SalePriceDetail detail = pricingService.resolveSalePriceDetail(
            rspuWithRetailPrice(null), rskuWithFactoryPrice(null));

        assertThat(detail.salePrice()).isNull();
        assertThat(detail.source()).isEqualTo(PricingService.SOURCE_NONE);
        assertThat(detail.appliedMultiplier()).isNull();
    }

    @Test
    void resolveMarkupMultiplierShouldPreferCategoryRule() {
        when(pricingRuleService.listAllRules()).thenReturn(Map.of("FS", rule("FS", "3.25")));

        assertThat(pricingService.resolveMarkupMultiplier("FS")).isEqualByComparingTo("3.25");
    }

    @Test
    void resolveMarkupMultiplierShouldFallbackToGlobal() {
        when(pricingRuleService.listAllRules()).thenReturn(Map.of());
        when(sysConfigMapper.selectById(ConfigService.MARKUP_GLOBAL_KEY)).thenReturn(null);

        assertThat(pricingService.resolveMarkupMultiplier("FS")).isEqualByComparingTo("2.5");
        // 品类编码为空直接回退全局，不查规则
        assertThat(pricingService.resolveMarkupMultiplier(null)).isEqualByComparingTo("2.5");
    }

    private PricingRule rule(String categoryCode, String multiplier) {
        PricingRule rule = new PricingRule();
        rule.setRuleId("PRULE-" + categoryCode);
        rule.setCategoryCode(categoryCode);
        rule.setMarkupMultiplier(new BigDecimal(multiplier));
        return rule;
    }

    @Test
    void isBelowCostShouldCompareSalePriceAndCost() {
        assertThat(PricingService.isBelowCost(new BigDecimal("90.00"), new BigDecimal("100.00"))).isTrue();
        assertThat(PricingService.isBelowCost(new BigDecimal("100.00"), new BigDecimal("100.00"))).isFalse();
        assertThat(PricingService.isBelowCost(new BigDecimal("110.00"), new BigDecimal("100.00"))).isFalse();
        assertThat(PricingService.isBelowCost(null, new BigDecimal("100.00"))).isFalse();
        assertThat(PricingService.isBelowCost(new BigDecimal("90.00"), null)).isFalse();
    }
}
