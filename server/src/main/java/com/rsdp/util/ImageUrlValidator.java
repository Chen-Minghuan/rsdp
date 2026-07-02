package com.rsdp.util;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;

/**
 * 图片 URL 安全校验工具。
 *
 * <p>防止 SSRF：限制只能访问公网 http/https 地址，默认禁止本地、内网和保留地址。</p>
 */
public final class ImageUrlValidator {

    private ImageUrlValidator() {
        // utility class
    }

    /**
     * 校验图片 URL 是否允许访问。
     *
     * @param url          图片 URL
     * @param allowedHosts 额外允许的 host 白名单（如测试环境可加入 127.0.0.1）
     * @return true 表示允许访问
     */
    public static boolean isAllowed(String url, java.util.Set<String> allowedHosts) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return false;
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        String lowerHost = host.toLowerCase();
        if (allowedHosts != null && allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(lowerHost))) {
            return true;
        }

        if (lowerHost.equals("localhost") || lowerHost.endsWith(".local")) {
            return false;
        }

        try {
            InetAddress address = InetAddress.getByName(host);
            return !isPrivateOrReserved(address);
        } catch (Exception e) {
            // 无法解析的域名默认放行，实际下载时仍会失败
            return true;
        }
    }

    private static boolean isPrivateOrReserved(InetAddress address) {
        byte[] bytes = address.getAddress();

        if (bytes.length == 4) {
            int b1 = bytes[0] & 0xFF;
            int b2 = bytes[1] & 0xFF;

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
