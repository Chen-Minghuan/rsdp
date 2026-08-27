package com.rsdp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.RateLimitProperties;
import com.rsdp.security.PublicRateLimitFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 公开接口限流过滤器注册。
 *
 * <p>通过 {@link FilterRegistrationBean} 显式注册而非 {@code @Component} 扫描：
 * 避免 {@code @WebMvcTest} 切片扫描 Filter 组件时因缺少配置属性 Bean 而失败。</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * 注册公开接口 IP 限流过滤器（仅 /api/v1/public/ai-match/** 与 POST /api/v1/public/leads 生效）。
     *
     * @param properties   限流配置
     * @param objectMapper JSON 序列化
     * @return 过滤器注册 Bean
     */
    @Bean
    public FilterRegistrationBean<PublicRateLimitFilter> publicRateLimitFilter(
        RateLimitProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<PublicRateLimitFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new PublicRateLimitFilter(properties, objectMapper));
        registration.addUrlPatterns("/api/v1/public/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }
}
