package com.rsdp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.common.Result;
import com.rsdp.config.properties.RateLimitProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 公开免登录接口的 IP 维度限流过滤器。
 *
 * <p>登录接口有 LoginAttemptService 防爆破，而免登录的公开付费接口（AI 户型搭配，
 * 每次调用消耗 DashScope 额度）与留资写入接口此前无任何频控，可被脚本刷量。
 * 本过滤器对这两类接口按「规则名 + 客户端 IP」做固定窗口计数限流，超限返回 429。</p>
 *
 * <p>单实例内存实现（ConcurrentHashMap + 惰性过期清理），与当前单后端部署形态匹配。</p>
 *
 * <p>注意：本类刻意不标 {@code @Component}——Spring Boot 的 {@code @WebMvcTest} 切片会
 * 扫描 Filter 组件但加载不了配置属性 Bean 导致 Controller 测试失败；改由
 * {@code config/RateLimitConfig} 以 FilterRegistrationBean 显式注册（不在切片扫描范围）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final String AI_MATCH_PREFIX = "/api/v1/public/ai-match/";
    private static final String LEADS_PATH = "/api/v1/public/leads";
    private static final long CLEANUP_INTERVAL_MS = 300_000;
    private static final int MAX_ENTRIES = 10_000;

    private final RateLimitProperties properties;
    private final ObjectMapper objectMapper;

    /** 计数表：key = 规则名|IP */
    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private volatile long lastCleanupAt = System.currentTimeMillis();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }
        Integer limit = resolveLimit(request);
        if (limit == null) {
            filterChain.doFilter(request, response);
            return;
        }
        maybeCleanup();

        String key = request.getRequestURI() + "|" + clientIp(request);
        WindowCounter counter = counters.computeIfAbsent(key, k -> new WindowCounter());
        long nowSec = System.currentTimeMillis() / 1000;
        synchronized (counter) {
            if (nowSec - counter.windowStartSec >= properties.getWindowSeconds()) {
                counter.windowStartSec = nowSec;
                counter.count.set(0);
            }
            if (counter.count.incrementAndGet() <= limit) {
                filterChain.doFilter(request, response);
                return;
            }
        }

        log.warn("公开接口限流触发，uri={}，ip={}，窗口上限={}/{}s",
            request.getRequestURI(), clientIp(request), limit, properties.getWindowSeconds());
        response.setStatus(429);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
            Result.error(429, "请求过于频繁，请稍后再试")));
    }

    /**
     * 匹配限流规则：AI 户型搭配全部端点 + 留资提交（仅 POST），其余公开读取接口不限。
     *
     * @param request 当前请求
     * @return 窗口内允许次数；不限流返回 null
     */
    private Integer resolveLimit(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri.startsWith(AI_MATCH_PREFIX)) {
            return properties.getAiMatchPermits();
        }
        if (LEADS_PATH.equals(uri) && "POST".equalsIgnoreCase(request.getMethod())) {
            return properties.getLeadsPermits();
        }
        return null;
    }

    /**
     * 解析客户端 IP：反向代理（nginx）后优先取 X-Forwarded-For 首跳，其次 X-Real-IP。
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(forwarded)) {
            return forwarded.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (StringUtils.hasText(realIp)) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 惰性清理过期窗口，防止计数表在大量不同 IP 下无界增长。
     */
    private void maybeCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupAt < CLEANUP_INTERVAL_MS) {
            return;
        }
        lastCleanupAt = now;
        if (counters.size() <= MAX_ENTRIES) {
            return;
        }
        long nowSec = now / 1000;
        counters.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                return nowSec - e.getValue().windowStartSec >= properties.getWindowSeconds();
            }
        });
    }

    /** 单个计数窗口（窗口起始秒 + 计数）。 */
    private static final class WindowCounter {
        private long windowStartSec = System.currentTimeMillis() / 1000;
        private final AtomicInteger count = new AtomicInteger(0);
    }
}
