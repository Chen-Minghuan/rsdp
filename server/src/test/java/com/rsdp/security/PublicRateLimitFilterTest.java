package com.rsdp.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.properties.RateLimitProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PublicRateLimitFilter} 单元测试。
 */
class PublicRateLimitFilterTest {

    private RateLimitProperties properties;
    private PublicRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        properties = new RateLimitProperties();
        properties.setEnabled(true);
        properties.setAiMatchPermits(3);
        properties.setLeadsPermits(2);
        properties.setWindowSeconds(60);
        filter = new PublicRateLimitFilter(properties, new ObjectMapper());
    }

    private MockHttpServletResponse perform(String uri, String method, String ip, int times) throws Exception {
        MockHttpServletResponse lastResponse = null;
        for (int i = 0; i < times; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
            request.setRemoteAddr(ip);
            lastResponse = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(request, lastResponse, chain);
            if (lastResponse.getStatus() == 429) {
                return lastResponse;
            }
        }
        return lastResponse;
    }

    @Test
    void shouldPassUnderLimit() throws Exception {
        MockHttpServletResponse response = perform("/api/v1/public/ai-match/scheme", "POST", "1.2.3.4", 3);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldReject429WhenOverLimit() throws Exception {
        MockHttpServletResponse response = perform("/api/v1/public/ai-match/analyze", "POST", "1.2.3.4", 4);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentAsString()).contains("请求过于频繁");
    }

    @Test
    void shouldCountIpsIndependently() throws Exception {
        perform("/api/v1/public/ai-match/scheme", "POST", "1.1.1.1", 3);

        // 另一个 IP 不受前者计数影响
        MockHttpServletResponse response = perform("/api/v1/public/ai-match/scheme", "POST", "2.2.2.2", 3);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldResetAfterWindowExpires() throws Exception {
        properties.setWindowSeconds(0); // 窗口立即过期，每次请求都重置计数

        MockHttpServletResponse response = perform("/api/v1/public/ai-match/scheme", "POST", "1.2.3.4", 10);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldLimitLeadsPostButNotGet() throws Exception {
        MockHttpServletResponse getResponse = perform("/api/v1/public/leads", "GET", "1.2.3.4", 10);
        assertThat(getResponse.getStatus()).isEqualTo(200);

        MockHttpServletResponse postResponse = perform("/api/v1/public/leads", "POST", "1.2.3.4", 3);
        assertThat(postResponse.getStatus()).isEqualTo(429);
    }

    @Test
    void shouldNotLimitOtherPublicReads() throws Exception {
        MockHttpServletResponse response = perform("/api/v1/public/products", "GET", "1.2.3.4", 20);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldPassThroughWhenDisabled() throws Exception {
        properties.setEnabled(false);

        MockHttpServletResponse response = perform("/api/v1/public/ai-match/scheme", "POST", "1.2.3.4", 10);

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void shouldResolveClientIpFromForwardedHeader() throws Exception {
        for (int i = 0; i < 3; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/public/ai-match/scheme");
            request.setRemoteAddr("10.0.0.1"); // 代理地址
            request.addHeader("X-Forwarded-For", "9.9.9.9, 10.0.0.1");
            filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        }

        // X-Forwarded-For 首跳 9.9.9.9 已达上限（3），下一次应 429
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/public/ai-match/scheme");
        request.setRemoteAddr("10.0.0.2");
        request.addHeader("X-Forwarded-For", "9.9.9.9, 10.0.0.2");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(429);
    }
}
