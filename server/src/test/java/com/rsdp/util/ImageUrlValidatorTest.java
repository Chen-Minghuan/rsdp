package com.rsdp.util;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ImageUrlValidator} 单元测试。
 */
class ImageUrlValidatorTest {

    @Test
    void shouldAllowPublicHttpUrl() {
        // 使用 host 白名单保证测试环境 DNS 不可解析时也能通过
        assertThat(ImageUrlValidator.isAllowed("http://example.com/image.jpg", Set.of("example.com"))).isTrue();
        assertThat(ImageUrlValidator.isAllowed("https://cdn.example.com/image.jpg", Set.of("cdn.example.com"))).isTrue();
    }

    @Test
    void shouldRejectNonHttpProtocol() {
        assertThat(ImageUrlValidator.isAllowed("ftp://example.com/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("file:///etc/passwd", Set.of())).isFalse();
    }

    @Test
    void shouldRejectLocalhost() {
        assertThat(ImageUrlValidator.isAllowed("http://localhost/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://localhost:8080/image.jpg", Set.of())).isFalse();
    }

    @Test
    void shouldRejectIpv4PrivateAddresses() {
        assertThat(ImageUrlValidator.isAllowed("http://127.0.0.1/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://10.0.0.1/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://192.168.1.1/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://172.16.0.1/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://169.254.1.1/image.jpg", Set.of())).isFalse();
    }

    @Test
    void shouldAllowWhitelistedHost() {
        assertThat(ImageUrlValidator.isAllowed("http://127.0.0.1/image.jpg", Set.of("127.0.0.1"))).isTrue();
        assertThat(ImageUrlValidator.isAllowed("http://localhost/image.jpg", Set.of("localhost"))).isTrue();
    }

    @Test
    void shouldRejectBlankOrMalformedUrl() {
        assertThat(ImageUrlValidator.isAllowed(null, Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("not-a-url", Set.of())).isFalse();
    }

    @Test
    void shouldRejectUrlWithUserInfo() {
        assertThat(ImageUrlValidator.isAllowed("http://10.0.0.1@example.com/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://user:pass@example.com/image.jpg", Set.of())).isFalse();
    }

    @Test
    void shouldRejectUnresolvableDomain() {
        // 使用理论上无法解析的域名，验证默认拒绝策略
        assertThat(ImageUrlValidator.isAllowed("http://rsdp-invalid-domain-99999.local/image.jpg", Set.of())).isFalse();
    }
}
