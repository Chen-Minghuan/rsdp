package com.rsdp.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

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

    @Test
    void decrypt_shouldFallbackForPlaintextPrice() {
        BigDecimal plaintext = new BigDecimal("12345.67");

        BigDecimal decrypted = AesEncryptionUtil.decrypt(plaintext.toPlainString());

        assertThat(decrypted).isEqualByComparingTo(plaintext);
    }

    // ---- R-06：兜底日志不得携带敏感值的可枚举派生信息（明文/长度/公开哈希） ----

    private ListAppender<ILoggingEvent> logAppender;

    @AfterEach
    void detachLogAppender() {
        if (logAppender != null) {
            ((Logger) LoggerFactory.getLogger(AesEncryptionUtil.class)).detachAppender(logAppender);
            logAppender.stop();
            logAppender = null;
        }
    }

    private ListAppender<ILoggingEvent> attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(AesEncryptionUtil.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
        return logAppender;
    }

    private static List<String> formattedMessages(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String sha256Base64(String raw) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }

    @Test
    void decrypt_fallbackForPlaintextPrice_shouldNotLogEnumerableDerivedInfo() throws Exception {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        String raw = "12345.67";

        BigDecimal decrypted = AesEncryptionUtil.decrypt(raw);

        assertThat(decrypted).isEqualByComparingTo(new BigDecimal(raw));
        List<String> messages = formattedMessages(appender);
        assertThat(messages).isNotEmpty();
        for (String message : messages) {
            assertThat(message)
                .doesNotContain(raw)
                .doesNotContain(sha256Base64(raw))
                .doesNotContain("长度")
                .doesNotContain("哈希");
        }
    }

    @Test
    void decrypt_failureForUnparseableValue_shouldNotLogEnumerableDerivedInfo() throws Exception {
        ListAppender<ILoggingEvent> appender = attachLogAppender();
        String raw = "not-base64!!!";

        assertThatThrownBy(() -> AesEncryptionUtil.decrypt(raw))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("解密失败");

        List<String> messages = formattedMessages(appender);
        assertThat(messages).isNotEmpty();
        for (String message : messages) {
            assertThat(message)
                .doesNotContain(raw)
                .doesNotContain(sha256Base64(raw))
                .doesNotContain("长度")
                .doesNotContain("哈希");
        }
        // 兜底失败路径应记录 ERROR 告警，便于运维发现异常密文
        assertThat(appender.list).anyMatch(event -> event.getLevel() == Level.ERROR);
    }
}
