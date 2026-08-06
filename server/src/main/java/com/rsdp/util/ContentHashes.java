package com.rsdp.util;

import java.security.MessageDigest;

/**
 * 内容哈希工具：SHA-256 十六进制摘要，供图片/文件内容查重使用。
 */
public final class ContentHashes {

    private ContentHashes() {
        // 工具类禁止实例化
    }

    /**
     * 计算字节内容的 SHA-256 十六进制哈希。
     *
     * @param bytes 内容字节（null 或空返回 null）
     * @return 64 位十六进制哈希字符串
     */
    public static String sha256Hex(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 SHA-256 失败", e);
        }
    }
}
