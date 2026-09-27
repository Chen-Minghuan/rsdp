package com.rsdp.supplychain.hash;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 敏感低熵值等值摘要（设计文档 §24.2 / §47.1.7 / §42.5）：HMAC-SHA-256。
 *
 * <p>精确采购成本等低熵敏感值禁止使用公开 SHA-256（可被离线枚举），统一使用：</p>
 *
 * <pre>
 * value_hmac = HMAC-SHA-256(secret_key_version_N, canonicalSensitiveValue)
 * </pre>
 *
 * <p>并随摘要保存 {@code hmac_key_version}。HMAC Key 与 Encryption Key 逻辑分离（本类只
 * 负责 HMAC，不负责加解密）。</p>
 *
 * <p>Key Rotation 语义（§42.5）：</p>
 * <ul>
 *   <li>{@link #sign} 只使用当前 Active Key Version —— 新 Sensitive Value 永远用新 Key；</li>
 *   <li>{@link #verify} 接受任意仍保留的 Key Version —— 历史摘要固定，旧 Key 仅验证保留；</li>
 *   <li>创建 Revision 时的 {@code value_hmac + hmac_key_version} 是固定 Hash Material，
 *       保留期内禁止就地重算或覆盖（本类不提供「重算并改写历史」的 API）；</li>
 *   <li>旧 Key 的销毁须先证明引用计数为零（调用方职责）。</li>
 * </ul>
 *
 * <p>实例不可变、线程安全。</p>
 */
public final class SensitiveValueHmac {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final Map<Integer, byte[]> keyByVersion;
    private final int activeKeyVersion;

    /**
     * @param keyByVersion    所有仍保留的 HMAC Key（version &gt; 0，key 非空）；
     *                        已退役但历史摘要仍引用着的 Key 保留在本 Map 中以维持「仅验证」能力
     * @param activeKeyVersion 当前 Active Key Version，必须存在于 keyByVersion
     */
    public SensitiveValueHmac(Map<Integer, byte[]> keyByVersion, int activeKeyVersion) {
        Objects.requireNonNull(keyByVersion, "keyByVersion 不能为 null");
        if (keyByVersion.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个 HMAC Key");
        }
        Map<Integer, byte[]> copy = new LinkedHashMap<>();
        keyByVersion.forEach((version, key) -> {
            if (version == null || version <= 0) {
                throw new IllegalArgumentException("hmac_key_version 必须 > 0");
            }
            if (key == null || key.length == 0) {
                throw new IllegalArgumentException("HMAC Key 不能为空: version=" + version);
            }
            copy.put(version, key.clone());
        });
        if (!copy.containsKey(activeKeyVersion)) {
            throw new IllegalArgumentException("activeKeyVersion 不存在于 keyByVersion: " + activeKeyVersion);
        }
        this.keyByVersion = Collections.unmodifiableMap(copy);
        this.activeKeyVersion = activeKeyVersion;
    }

    /**
     * 用当前 Active Key 签名（新值永远用新 Key）。
     *
     * @param canonicalSensitiveValue 规范化后的敏感值文本（如价格规范十进制），禁止 null
     * @return 摘要与所使用的 Key Version
     */
    public SignedValue sign(String canonicalSensitiveValue) {
        Objects.requireNonNull(canonicalSensitiveValue, "敏感值不能为 null");
        return new SignedValue(hmacHex(keyByVersion.get(activeKeyVersion), canonicalSensitiveValue),
            activeKeyVersion);
    }

    /**
     * 用指定历史 Key Version 做常量时间验证（旧 Key 仅验证保留）。
     *
     * @param canonicalSensitiveValue 规范化后的敏感值文本
     * @param expectedHmacHex         存储的 value_hmac（64 位十六进制）
     * @param hmacKeyVersion          存储的 hmac_key_version
     * @return 是否匹配；未知 / 已销毁的 Key Version 一律返回 false
     */
    public boolean verify(String canonicalSensitiveValue, String expectedHmacHex, int hmacKeyVersion) {
        if (canonicalSensitiveValue == null || expectedHmacHex == null) {
            return false;
        }
        byte[] key = keyByVersion.get(hmacKeyVersion);
        if (key == null) {
            return false;
        }
        String actual = hmacHex(key, canonicalSensitiveValue);
        return MessageDigest.isEqual(
            actual.getBytes(StandardCharsets.UTF_8),
            expectedHmacHex.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 当前 Active Key Version。
     *
     * @return active key version
     */
    public int activeKeyVersion() {
        return activeKeyVersion;
    }

    /**
     * 仍保留（可验证）的全部 Key Version。
     *
     * @return key versions
     */
    public Set<Integer> supportedKeyVersions() {
        return keyByVersion.keySet();
    }

    private static String hmacHex(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("计算 HMAC-SHA-256 失败", e);
        }
    }

    /**
     * 签名结果：value_hmac 与生成它的 hmac_key_version（§24.2 两列必须同时持久化）。
     *
     * @param valueHmac      64 位小写十六进制 HMAC-SHA-256
     * @param hmacKeyVersion 生成该摘要的 Key Version
     */
    public record SignedValue(String valueHmac, int hmacKeyVersion) {
    }
}
