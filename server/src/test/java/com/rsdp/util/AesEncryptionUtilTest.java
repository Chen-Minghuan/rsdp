package com.rsdp.util;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AesEncryptionUtil} 单元测试。
 */
class AesEncryptionUtilTest {

    @BeforeAll
    static void setUp() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        Field field = AesEncryptionUtil.class.getDeclaredField("secretKey");
        field.setAccessible(true);
        field.set(null, secretKey);
    }

    @Test
    void encryptDecrypt_shouldRestoreOriginalValue() {
        BigDecimal original = new BigDecimal("1234.56");

        String encrypted = AesEncryptionUtil.encrypt(original);
        BigDecimal decrypted = AesEncryptionUtil.decrypt(encrypted);

        assertThat(encrypted).isNotNull().isNotEqualTo(original.toPlainString());
        assertThat(decrypted).isEqualByComparingTo(original);
    }

    @Test
    void encrypt_shouldProduceDifferentCipherForSamePlaintext() {
        BigDecimal original = new BigDecimal("999.99");

        String encrypted1 = AesEncryptionUtil.encrypt(original);
        String encrypted2 = AesEncryptionUtil.encrypt(original);

        assertThat(encrypted1).isNotEqualTo(encrypted2);
        assertThat(AesEncryptionUtil.decrypt(encrypted1)).isEqualByComparingTo(original);
        assertThat(AesEncryptionUtil.decrypt(encrypted2)).isEqualByComparingTo(original);
    }

    @Test
    void encryptDecrypt_shouldHandleNull() {
        assertThat(AesEncryptionUtil.encrypt(null)).isNull();
        assertThat(AesEncryptionUtil.decrypt(null)).isNull();
        assertThat(AesEncryptionUtil.decrypt("")).isNull();
    }

    @Test
    void decrypt_shouldFailForInvalidBase64() {
        assertThatThrownBy(() -> AesEncryptionUtil.decrypt("not-base64!!!"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("解密失败");
    }

    @Test
    void decrypt_shouldFailForTamperedCipher() {
        BigDecimal original = new BigDecimal("888.88");
        String encrypted = AesEncryptionUtil.encrypt(original);

        // 翻转密文中的一个字符
        char[] chars = encrypted.toCharArray();
        chars[chars.length - 1] = chars[chars.length - 1] == 'A' ? 'B' : 'A';
        String tampered = new String(chars);

        assertThatThrownBy(() -> AesEncryptionUtil.decrypt(tampered))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("解密失败");
    }
}
