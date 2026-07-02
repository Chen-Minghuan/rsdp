package com.rsdp.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * 敏感字段加密配置。
 *
 * <p>从 {@code rsdp.encryption.key} 读取 Base64 编码的 32 字节 AES-256 密钥。
 * 生产环境必须通过 {@code RSDP_ENCRYPTION_KEY} 环境变量注入，禁止在配置文件中硬编码真实密钥。</p>
 */
@Slf4j
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.encryption")
public class EncryptionProperties {

    /**
     * Base64 编码的 AES-256 密钥（32 字节）。
     */
    private String key;

    private byte[] decodedKey;

    @PostConstruct
    public void init() {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(
                "缺少加密密钥配置：rsdp.encryption.key。" +
                "请通过 RSDP_ENCRYPTION_KEY 环境变量注入 Base64 编码的 32 字节 AES-256 密钥。"
            );
        }
        try {
            decodedKey = Base64.getDecoder().decode(key.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("加密密钥不是有效的 Base64 字符串: " + e.getMessage(), e);
        }
        if (decodedKey.length != 32) {
            throw new IllegalStateException(
                "加密密钥长度错误：期望 32 字节（256 bit），实际 " + decodedKey.length + " 字节。" +
                "请生成 32 字节随机密钥并使用 Base64 编码后注入。"
            );
        }
        log.info("加密密钥已加载，长度 {} 字节", decodedKey.length);
    }
}
