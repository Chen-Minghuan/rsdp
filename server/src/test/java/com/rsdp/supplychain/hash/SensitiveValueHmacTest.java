package com.rsdp.supplychain.hash;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SensitiveValueHmac} 单元测试（§24.2 / §47.1.7 / §42.5 Key Rotation）。
 */
class SensitiveValueHmacTest {

    private static final byte[] KEY_V1 = "rsdp-test-hmac-key-v1".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_V2 = "rsdp-test-hmac-key-v2".getBytes(StandardCharsets.UTF_8);

    private SensitiveValueHmac hmacV1Active() {
        return new SensitiveValueHmac(Map.of(1, KEY_V1), 1);
    }

    private SensitiveValueHmac hmacV2ActiveWithV1Retained() {
        return new SensitiveValueHmac(Map.of(1, KEY_V1, 2, KEY_V2), 2);
    }

    @Test
    void sign_shouldMatchFixedVector() {
        // 跨语言固定向量：HMAC-SHA-256("rsdp-test-hmac-key-v1", "9500")
        SensitiveValueHmac.SignedValue signed = hmacV1Active().sign("9500");
        assertEquals("ac38f79f02787305d2923038203a6d421c78c0d6fc5c8b9f135141b5b18bfecc",
            signed.valueHmac());
        assertEquals(1, signed.hmacKeyVersion());
    }

    @Test
    void sign_shouldAlwaysUseActiveKeyVersion() {
        SensitiveValueHmac rotated = hmacV2ActiveWithV1Retained();
        SensitiveValueHmac.SignedValue signed = rotated.sign("9500");
        assertEquals(2, signed.hmacKeyVersion());
        assertEquals("5687b9fdd0962c5c214c6cec93ad87afc5723bf1769ed9c352348f7bcaf65158",
            signed.valueHmac());
        // 同一值在新旧 Key 下摘要不同
        assertNotEquals(hmacV1Active().sign("9500").valueHmac(), signed.valueHmac());
    }

    @Test
    void sign_shouldBeDeterministic() {
        SensitiveValueHmac hmac = hmacV2ActiveWithV1Retained();
        String expected = hmac.sign("9500").valueHmac();
        for (int i = 0; i < 50; i++) {
            assertEquals(expected, hmac.sign("9500").valueHmac());
        }
    }

    @Test
    void sign_shouldDistinguishCanonicalValues() {
        SensitiveValueHmac hmac = hmacV1Active();
        // "9500" 与 "9500.00" 是不同的 canonical 值，摘要必须不同（规范化是调用方职责）
        assertNotEquals(hmac.sign("9500").valueHmac(), hmac.sign("9500.00").valueHmac());
        assertEquals("f31a9f3676809afb8ef09b2f22bf66c423a6977196b22a1b3b99fd757a395d88",
            hmac.sign("9500.00").valueHmac());
    }

    @Test
    void verify_shouldAcceptHistoricalKeyVersion() {
        // §42.5：历史摘要固定，旧 Key 仅验证保留
        String historical = hmacV1Active().sign("9500").valueHmac();
        SensitiveValueHmac rotated = hmacV2ActiveWithV1Retained();
        assertTrue(rotated.verify("9500", historical, 1));
        assertTrue(rotated.verify("9500",
            "5687b9fdd0962c5c214c6cec93ad87afc5723bf1769ed9c352348f7bcaf65158", 2));
    }

    @Test
    void verify_shouldRejectWrongValueOrVersion() {
        SensitiveValueHmac rotated = hmacV2ActiveWithV1Retained();
        String v1 = hmacV1Active().sign("9500").valueHmac();
        // 值不同
        assertFalse(rotated.verify("9501", v1, 1));
        // 用错误版本验证（HMAC Key Version 不同的摘要不得判等，§47.1.5 quoteValueSame 口径）
        assertFalse(rotated.verify("9500", v1, 2));
        // 未知 / 已销毁版本
        assertFalse(rotated.verify("9500", v1, 99));
        // 非十六进制 / 长度不符
        assertFalse(rotated.verify("9500", "not-hex", 1));
        assertFalse(rotated.verify("9500", v1.substring(0, 32), 1));
        // null 入参
        assertFalse(rotated.verify(null, v1, 1));
        assertFalse(rotated.verify("9500", null, 1));
    }

    @Test
    void constructor_shouldValidateKeys() {
        assertThrows(IllegalArgumentException.class, () -> new SensitiveValueHmac(Map.of(), 1));
        assertThrows(IllegalArgumentException.class,
            () -> new SensitiveValueHmac(Map.of(1, KEY_V1), 2));
        assertThrows(IllegalArgumentException.class,
            () -> new SensitiveValueHmac(Map.of(0, KEY_V1), 0));
        assertThrows(IllegalArgumentException.class,
            () -> new SensitiveValueHmac(Map.of(1, new byte[0]), 1));
        assertThrows(NullPointerException.class, () -> new SensitiveValueHmac(null, 1));
    }

    @Test
    void constructor_shouldDefensivelyCopyKeys() {
        byte[] key = KEY_V1.clone();
        SensitiveValueHmac hmac = new SensitiveValueHmac(Map.of(1, key), 1);
        key[0] = (byte) (key[0] ^ 0xFF);
        assertTrue(hmac.verify("9500",
            "ac38f79f02787305d2923038203a6d421c78c0d6fc5c8b9f135141b5b18bfecc", 1));
    }

    @Test
    void shouldExposeVersions() {
        SensitiveValueHmac rotated = hmacV2ActiveWithV1Retained();
        assertEquals(2, rotated.activeKeyVersion());
        assertEquals(Set.of(1, 2), rotated.supportedKeyVersions());
    }
}
