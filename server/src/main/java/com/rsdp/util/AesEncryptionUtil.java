package com.rsdp.util;

import com.rsdp.config.EncryptionProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密工具，用于敏感价格字段加解密。
 *
 * <p>密文格式：{@code Base64(12 字节 IV + ciphertext + 16 字节 authTag)}。
 * 由于 GCM 模式下 tag 会附加在 ciphertext 末尾，因此最终存储为统一 Base64 字符串。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AesEncryptionUtil {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;

    private final EncryptionProperties properties;

    private static SecretKey secretKey;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @PostConstruct
    public void init() {
        secretKey = new SecretKeySpec(properties.getDecodedKey(), ALGORITHM);
    }

    /**
     * 加密 BigDecimal 价格。
     *
     * @param value 明文价格
     * @return Base64 编码的密文
     */
    public static String encrypt(BigDecimal value) {
        if (value == null) {
            return null;
        }
        return encrypt(value.toPlainString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 解密为 BigDecimal 价格。
     *
     * @param encrypted Base64 编码的密文
     * @return 明文价格
     */
    public static BigDecimal decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encrypted);
            byte[] plainBytes = decrypt(decoded);
            return new BigDecimal(new String(plainBytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            log.error("密文 Base64 解码失败", e);
            throw new IllegalStateException("AES 解密失败: 密文格式错误", e);
        }
    }

    private static String encrypt(byte[] plainBytes) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] cipherBytes = cipher.doFinal(plainBytes);

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherBytes.length);
            byteBuffer.put(iv);
            byteBuffer.put(cipherBytes);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            log.error("AES 加密失败", e);
            throw new IllegalStateException("AES 加密失败: " + e.getMessage(), e);
        }
    }

    private static byte[] decrypt(byte[] encryptedBytes) {
        try {
            if (encryptedBytes.length < GCM_IV_LENGTH + 1) {
                throw new IllegalArgumentException("密文长度不足");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(encryptedBytes);
            byte[] iv = new byte[GCM_IV_LENGTH];
            byteBuffer.get(iv);
            byte[] cipherBytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(cipherBytes);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            return cipher.doFinal(cipherBytes);
        } catch (Exception e) {
            log.error("AES 解密失败", e);
            throw new IllegalStateException("AES 解密失败: " + e.getMessage(), e);
        }
    }
}
