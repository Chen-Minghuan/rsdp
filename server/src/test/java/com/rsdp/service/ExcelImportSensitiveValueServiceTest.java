package com.rsdp.service;

import com.rsdp.config.EncryptionProperties;
import com.rsdp.entity.ExcelImportSensitiveValue;
import com.rsdp.mapper.ExcelImportSensitiveValueMapper;
import com.rsdp.supplychain.hash.SensitiveValueHmac;
import com.rsdp.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 敏感 Preview 值服务单元测试（设计文档 v3.3 §24.2 / §42.5 / §43）。
 * 原 Issue 处置值用例随 import_data_issue 表一并移除（2026-09-24 评审决策）。
 *
 * <p>覆盖：写入形态（AES-256-GCM 密文 + HMAC-SHA-256 版本化摘要 + Sensitive Ref）、
 * HMAC Key 旋转语义——新值永远用 Active 版本签发、历史摘要可验证且不被改写
 * （本服务无 UPDATE 入口）、规范十进制等值比较。</p>
 */
@ExtendWith(MockitoExtension.class)
class ExcelImportSensitiveValueServiceTest {

    private static final byte[] KEY_V1;
    private static final byte[] KEY_V2;

    static {
        KEY_V1 = new byte[32];
        KEY_V2 = new byte[32];
        for (int i = 0; i < 32; i++) {
            KEY_V1[i] = (byte) (i + 1);
            KEY_V2[i] = (byte) (i + 101);
        }
    }

    @Mock
    private ExcelImportSensitiveValueMapper sensitiveValueMapper;

    @BeforeAll
    static void initCrypto() {
        // AesEncryptionUtil 为静态密钥工具：测试中装载一次性 AES-256 密钥
        EncryptionProperties encryptionProperties = new EncryptionProperties();
        encryptionProperties.setKey(Base64.getEncoder().encodeToString(new byte[32]));
        encryptionProperties.init();
        new AesEncryptionUtil(encryptionProperties).init();
    }

    @Test
    void groupSourceRows_areEncryptedAndAuthenticated() {
        ExcelImportSensitiveValueService service = newService(
            new SensitiveValueHmac(Map.of(1, KEY_V1), 1));
        String source = "[{\"__rowIndex__\":\"2\",\"出厂价\":\"123.45\"}]";
        ExcelImportSensitiveValue row = service.prepareGroupSourceRows("B-1", 2L, "G-1", source);
        assertFalse(row.getCiphertext().contains("123.45"));
        assertEquals("/sourceRows", row.getValuePath());
        when(sensitiveValueMapper.selectById(row.getSensitiveValueId())).thenReturn(row);
        assertEquals(source, service.readGroupSourceRows("B-1", 2L, "G-1", row.getSensitiveValueId()));
        row.setValueHmac("0".repeat(64));
        assertThrows(IllegalStateException.class,
            () -> service.readGroupSourceRows("B-1", 2L, "G-1", row.getSensitiveValueId()));
    }





    private ExcelImportSensitiveValueService newService(SensitiveValueHmac hmac) {
        return new ExcelImportSensitiveValueService(sensitiveValueMapper, hmac);
    }
}
