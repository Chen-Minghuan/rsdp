package com.rsdp.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * 图片 URL 安全校验工具。
 *
 * <p>防止 SSRF：限制只能访问公网 http/https 地址，默认禁止本地、内网和保留地址。</p>
 */
public final class ImageUrlValidator {

    /** 允许的 URL scheme（统一小写后比较）。 */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /** 允许的显式端口；未显式指定端口（-1）时使用协议默认端口，视为允许。 */
    private static final Set<Integer> ALLOWED_PORTS = Set.of(80, 443);

    private ImageUrlValidator() {
        // utility class
    }

    /**
     * 校验图片 URL 是否允许访问。
     *
     * <p>白名单中的 host 由运维显式配置（如开发环境的 127.0.0.1），视为可信，
     * 跳过端口与 DNS 内网检查；其余校验（scheme、userinfo、host 非空）仍然生效。</p>
     *
     * @param url          图片 URL
     * @param allowedHosts 额外允许的 host 白名单（如测试环境可加入 127.0.0.1）
     * @return true 表示允许访问
     */
    public static boolean isAllowed(String url, Set<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return false;
        }

        // scheme 统一小写后校验，仅允许 http/https
        String scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT))) {
            return false;
        }

        // 拒绝带 userinfo 的 URL，防止 @ 绕过（如 http://10.0.0.1@example.com）
        if (uri.getUserInfo() != null && !uri.getUserInfo().isBlank()) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        String lowerHost = host.toLowerCase(Locale.ROOT);
        if (allowedHosts != null && allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(lowerHost))) {
            return true;
        }

        // 限制显式端口：仅允许常见图片 CDN 端口，未指定端口（协议默认）视为允许
        int port = uri.getPort();
        if (port != -1 && !ALLOWED_PORTS.contains(port)) {
            return false;
        }

        if (lowerHost.equals("localhost") || lowerHost.endsWith(".local")) {
            return false;
        }

        // DNS rebinding 防护：校验解析出的所有 IP，而非仅第一个 A 记录
        return areAllResolvedAddressesPublic(host);
    }

    /**
     * DNS rebinding 防护：对 host 解析出的所有 IP 复检，任一结果为内网/保留地址即视为不安全。
     *
     * <p>供下载方在建立连接前调用，防止校验通过到实际连接之间 DNS 记录被篡改（rebinding）。
     * 无法解析的域名按不安全处理。</p>
     *
     * @param host 主机名或 IP 字面量
     * @return true 表示所有解析结果均为公网地址
     */
    public static boolean areAllResolvedAddressesPublic(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                return false;
            }
            for (InetAddress address : addresses) {
                if (isPrivateOrReserved(address)) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            // 无法解析的域名默认拒绝，防止通过 DNS 绕过内网限制
            return false;
        }
    }

    private static boolean isPrivateOrReserved(InetAddress address) {
        // 先走 JDK 内建判定：0.0.0.0/::、环回、链路本地、站点本地、组播
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }

        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {
            int b1 = bytes[0] & 0xFF;
            int b2 = bytes[1] & 0xFF;

            // 0.0.0.0/8（"本网络"整段，JDK 仅识别 0.0.0.0 单个地址）
            if (b1 == 0) {
                return true;
            }
            // 127.0.0.0/8
            if (b1 == 127) {
                return true;
            }
            // 10.0.0.0/8
            if (b1 == 10) {
                return true;
            }
            // 172.16.0.0/12
            if (b1 == 172 && b2 >= 16 && b2 <= 31) {
                return true;
            }
            // 192.168.0.0/16
            if (b1 == 192 && b2 == 168) {
                return true;
            }
            // 169.254.0.0/16 (link-local)
            if (b1 == 169 && b2 == 254) {
                return true;
            }
            // 100.64.0.0/10 (CGNAT 共享地址段, RFC 6598)
            if (b1 == 100 && b2 >= 64 && b2 <= 127) {
                return true;
            }
            // 192.0.0.0/24 (IETF 协议分配, RFC 6890)
            if (b1 == 192 && b2 == 0 && (bytes[2] & 0xFF) == 0) {
                return true;
            }
            // 198.18.0.0/15 (网络基准测试, RFC 2544)
            if (b1 == 198 && (b2 == 18 || b2 == 19)) {
                return true;
            }
            // 240.0.0.0/4 (保留地址段, 含 255.255.255.255 广播)
            if (b1 >= 240) {
                return true;
            }
            return false;
        }

        if (bytes.length == 16) {
            // ::1
            boolean isLoopback = true;
            for (int i = 0; i < 15; i++) {
                if (bytes[i] != 0) {
                    isLoopback = false;
                    break;
                }
            }
            if (isLoopback && bytes[15] == 1) {
                return true;
            }

            int high = (bytes[0] & 0xFF) << 8 | (bytes[1] & 0xFF);
            // fe80::/10
            if ((high & 0xFFC0) == 0xFE80) {
                return true;
            }
            // fc00::/7
            if ((high & 0xFE00) == 0xFC00) {
                return true;
            }
        }

        return false;
    }
}
