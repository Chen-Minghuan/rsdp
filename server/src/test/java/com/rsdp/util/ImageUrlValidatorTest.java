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

    @Test
    void shouldAllowUppercaseSchemeAfterNormalization() {
        // scheme 统一小写后校验，大写 HTTP/HTTPS 应视为合法
        assertThat(ImageUrlValidator.isAllowed("HTTP://example.com/image.jpg", Set.of("example.com"))).isTrue();
        assertThat(ImageUrlValidator.isAllowed("HTTPS://example.com/image.jpg", Set.of("example.com"))).isTrue();
    }

    @Test
    void shouldRejectNonStandardExplicitPort() {
        // 使用公网 IP 字面量，避免依赖 DNS 解析
        assertThat(ImageUrlValidator.isAllowed("http://8.8.8.8:8080/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://8.8.8.8:9000/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("https://8.8.8.8:8443/image.jpg", Set.of())).isFalse();
        assertThat(ImageUrlValidator.isAllowed("http://8.8.8.8:22/image.jpg", Set.of())).isFalse();
    }

    @Test
    void shouldAllowDefaultAndStandardPorts() {
        // 未显式指定端口（协议默认）以及显式 80/443 均允许
        assertThat(ImageUrlValidator.isAllowed("http://8.8.8.8/image.jpg", Set.of())).isTrue();
        assertThat(ImageUrlValidator.isAllowed("http://8.8.8.8:80/image.jpg", Set.of())).isTrue();
        assertThat(ImageUrlValidator.isAllowed("https://8.8.8.8:443/image.jpg", Set.of())).isTrue();
    }

    @Test
    void shouldAllowWhitelistedHostWithNonStandardPort() {
        // 白名单 host 为运维显式配置的可信地址（如开发环境 MinIO），跳过端口限制
        assertThat(ImageUrlValidator.isAllowed("http://127.0.0.1:9000/image.jpg", Set.of("127.0.0.1"))).isTrue();
    }

    @Test
    void shouldRejectAnyLocalAddress() {
        assertThat(ImageUrlValidator.isAllowed("http://0.0.0.0/image.jpg", Set.of())).isFalse();
    }

    @Test
    void shouldRecheckAllResolvedAddressesAgainstRebinding() {
        // DNS rebinding 复检：解析结果为内网/保留地址即拒绝
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("localhost")).isFalse();
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("127.0.0.1")).isFalse();
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("10.0.0.1")).isFalse();
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("0.0.0.0")).isFalse();
        // IP 字面量不触发真实 DNS 查询，可离线运行
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("8.8.8.8")).isTrue();
        // 无法解析或入参非法按不安全处理
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("rsdp-invalid-domain-99999.local")).isFalse();
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic(null)).isFalse();
        assertThat(ImageUrlValidator.areAllResolvedAddressesPublic("")).isFalse();
    }
}
