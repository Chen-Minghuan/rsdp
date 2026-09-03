package com.rsdp.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.config.CacheConfig;
import com.rsdp.dto.request.PricingRuleUpsertRequest;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.PricingRule;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.mapper.CategoryDictMapper;
import com.rsdp.mapper.PricingRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PricingRuleService} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PricingRuleServiceTest {

    @Mock
    private PricingRuleMapper pricingRuleMapper;

    @Mock
    private CategoryDictMapper categoryDictMapper;

    @Mock
    private AuditLogService auditLogService;

    private PricingRuleService newService() {
        return new PricingRuleService(pricingRuleMapper, categoryDictMapper, auditLogService);
    }

    private PricingRule rule(String categoryCode, String multiplier) {
        PricingRule rule = new PricingRule();
        rule.setRuleId("PRULE-" + categoryCode);
        rule.setCategoryCode(categoryCode);
        rule.setMarkupMultiplier(new BigDecimal(multiplier));
        return rule;
    }

    private PricingRuleUpsertRequest req(String multiplier) {
        PricingRuleUpsertRequest request = new PricingRuleUpsertRequest();
        request.setMarkupMultiplier(multiplier != null ? new BigDecimal(multiplier) : null);
        return request;
    }

    @Test
    void listAllRulesShouldIndexByCategoryCode() {
        when(pricingRuleMapper.selectList(null)).thenReturn(List.of(rule("FS", "3.0"), rule("CH", "2.8")));

        var rules = newService().listAllRules();

        assertThat(rules).hasSize(2);
        assertThat(rules.get("FS").getMarkupMultiplier()).isEqualByComparingTo("3.0");
        assertThat(rules.get("CH").getMarkupMultiplier()).isEqualByComparingTo("2.8");
    }

    @Test
    void listRulesShouldJoinCategoryNames() {
        when(pricingRuleMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(rule("FS", "3.0")));
        CategoryDict dict = new CategoryDict();
        dict.setDictCode("FS");
        dict.setDictName("沙发");
        when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(dict));

        var rules = newService().listRules();

        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getCategoryCode()).isEqualTo("FS");
        assertThat(rules.get(0).getCategoryName()).isEqualTo("沙发");
    }

    @Test
    void upsertRuleShouldInsertWhenCategoryMissing() {
        when(pricingRuleMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var response = newService().upsertRule("FS", req("3.0"));

        assertThat(response.getCategoryCode()).isEqualTo("FS");
        assertThat(response.getMarkupMultiplier()).isEqualByComparingTo("3.0");
        assertThat(response.getRuleId()).startsWith("PRULE-");
        verify(pricingRuleMapper).insert(any(PricingRule.class));
        verify(auditLogService).logCreate(eq("pricing_rule"), any(), any(PricingRule.class), any());
    }

    @Test
    void upsertRuleShouldUpdateWhenCategoryExists() {
        PricingRule existing = rule("FS", "3.0");
        when(pricingRuleMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);
        when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var response = newService().upsertRule("FS", req("3.5"));

        assertThat(response.getRuleId()).isEqualTo("PRULE-FS");
        assertThat(response.getMarkupMultiplier()).isEqualByComparingTo("3.5");
        verify(pricingRuleMapper).updateById(any(PricingRule.class));
        verify(auditLogService).logUpdate(eq("pricing_rule"), eq("PRULE-FS"), any(), any(PricingRule.class), any());
    }

    @Test
    void upsertRuleShouldRejectNonPositiveMultiplier() {
        assertThatThrownBy(() -> newService().upsertRule("FS", req("0")))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("大于 0");
        assertThatThrownBy(() -> newService().upsertRule("FS", req("-1")))
            .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> newService().upsertRule("FS", req(null)))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void deleteRuleShouldDeleteAndAudit() {
        PricingRule existing = rule("FS", "3.0");
        when(pricingRuleMapper.selectOne(any(QueryWrapper.class))).thenReturn(existing);

        newService().deleteRule("FS");

        verify(pricingRuleMapper).deleteById("PRULE-FS");
        verify(auditLogService).logDelete(eq("pricing_rule"), eq("PRULE-FS"), any(PricingRule.class), any());
    }

    @Test
    void deleteRuleShouldRejectMissingRule() {
        when(pricingRuleMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThatThrownBy(() -> newService().deleteRule("FS"))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("FS");
    }

    @Test
    void categoryRuleCacheShouldEvictOnUpsert() {
        // 通过最小 Spring 上下文（@EnableCaching 代理链）验证 @Cacheable/@CacheEvict 真实生效：
        // 缓存命中 → upsert 失效 → 重读新值
        PricingRuleService target = newService();
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.register(org.springframework.cache.annotation.ProxyCachingConfiguration.class);
            ctx.register(org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator.class);
            ctx.registerBean("cacheManager",
                org.springframework.cache.concurrent.ConcurrentMapCacheManager.class,
                () -> new org.springframework.cache.concurrent.ConcurrentMapCacheManager(CacheConfig.CACHE_NAME_PRICING_RULE));
            ctx.registerBean("pricingRuleService", PricingRuleService.class, () -> target);
            ctx.refresh();
            PricingRuleService proxied = ctx.getBean(PricingRuleService.class);

            when(pricingRuleMapper.selectList(null)).thenReturn(List.of(rule("FS", "3.0")));
            assertThat(proxied.listAllRules().get("FS").getMarkupMultiplier()).isEqualByComparingTo("3.0");

            // 第二次读走缓存：底层数据已变为 3.5 但缓存仍返回 3.0
            when(pricingRuleMapper.selectList(null)).thenReturn(List.of(rule("FS", "3.5")));
            assertThat(proxied.listAllRules().get("FS").getMarkupMultiplier()).isEqualByComparingTo("3.0");

            // upsert 触发缓存失效，重读拿到新倍率
            when(pricingRuleMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
            lenient().when(categoryDictMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
            proxied.upsertRule("FS", req("3.5"));

            assertThat(proxied.listAllRules().get("FS").getMarkupMultiplier()).isEqualByComparingTo("3.5");
        }
    }
}
