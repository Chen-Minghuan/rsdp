package com.rsdp.config;

import com.rsdp.config.properties.ExcelImportGovernanceProperties;
import com.rsdp.supplychain.hash.SensitiveValueHmac;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel 导入敏感值安全装配（设计文档 v3.3 §42.5 / §43.2，P0-B3）。
 *
 * <p>把版本化 HMAC keyring 装配成 {@link SensitiveValueHmac} Bean：</p>
 * <ul>
 *   <li>Key 只经配置注入（生产：KMS / Vault → 环境变量），明文不入库入仓入日志；</li>
 *   <li>新 Sensitive Value 永远用 Active 版本签发；已退役版本保留在 keyring 中
 *       维持「仅验证」能力，直到引用它的 Snapshot 到期或解除 legal hold——
 *       旋转不改写历史 Hash Material（Bean 不提供任何重算改写入口）；</li>
 *   <li>开发环境未配置时生成一次性临时 Key 并 WARN（重启后历史摘要不可验证）；
 *       生产环境必须通过 {@code rsdp.excel-import.hmac-keys} 显式注入。</li>
 * </ul>
 *
 * <p>AES Encryption Key 的 versioned keyring 升级（现有 {@code AesEncryptionUtil}
 * 单把静态 Key）按 §43.2 收口归 P0-E，本类只负责 HMAC 侧。</p>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ExcelImportSecurityConfig {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final ExcelImportGovernanceProperties properties;

    @Bean
    public SensitiveValueHmac sensitiveValueHmac() {
        Map<Integer, byte[]> keyring = new LinkedHashMap<>();
        properties.getHmacKeys().forEach((version, base64) -> {
            if (version == null || version <= 0) {
                throw new IllegalStateException("hmac key version 必须 > 0: " + version);
            }
            byte[] key;
            try {
                key = Base64.getDecoder().decode(base64.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("HMAC Key 不是有效的 Base64: version=" + version, e);
            }
            if (key.length < 32) {
                throw new IllegalStateException(
                    "HMAC Key 长度不足（§43.2 要求 ≥ 256 bit）: version=" + version);
            }
            keyring.put(version, key);
        });
        int active = properties.getActiveHmacKeyVersion();
        if (keyring.isEmpty()) {
            // 开发兜底：未配置时生成一次性临时 Key，避免阻断本地启动；生产必须显式配置
            byte[] ephemeral = new byte[32];
            SECURE_RANDOM.nextBytes(ephemeral);
            keyring.put(1, ephemeral);
            log.warn("rsdp.excel-import.hmac-keys 未配置：已生成一次性临时 HMAC Key（version=1），"
                + "重启后历史敏感值摘要不可验证。生产环境必须通过环境变量注入版本化 keyring");
            return new SensitiveValueHmac(keyring, 1);
        }
        if (!keyring.containsKey(active)) {
            throw new IllegalStateException("active-hmac-key-version 不在 hmac-keys 中: " + active);
        }
        log.info("敏感值 HMAC keyring 已加载：versions={}, active={}", keyring.keySet(), active);
        return new SensitiveValueHmac(keyring, active);
    }
}
