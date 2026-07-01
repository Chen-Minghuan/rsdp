package com.rsdp.config.typehandler;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link EncryptTypeHandler} 单元测试。
 */
class EncryptTypeHandlerTest {

    private final EncryptTypeHandler handler = new EncryptTypeHandler();

    @BeforeAll
    static void setUp() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        Field field = com.rsdp.util.AesEncryptionUtil.class.getDeclaredField("secretKey");
        field.setAccessible(true);
        field.set(null, secretKey);
    }

    @Test
    void setNonNullParameter_shouldEncryptValue() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        BigDecimal value = new BigDecimal("1234.56");

        handler.setNonNullParameter(ps, 1, value, null);

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(ps).setString(org.mockito.ArgumentMatchers.eq(1), captor.capture());
        String encrypted = captor.getValue();
        assertThat(encrypted).isNotNull().isNotEqualTo(value.toPlainString());
    }

    @Test
    void getNullableResult_shouldDecryptValue() throws Exception {
        BigDecimal original = new BigDecimal("9876.54");
        String encrypted = com.rsdp.util.AesEncryptionUtil.encrypt(original);

        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("factory_price")).thenReturn(encrypted);

        BigDecimal result = handler.getNullableResult(rs, "factory_price");

        assertThat(result).isEqualByComparingTo(original);
    }

    @Test
    void getNullableResult_shouldReturnNullForBlank() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("factory_price")).thenReturn("");

        BigDecimal result = handler.getNullableResult(rs, "factory_price");

        assertThat(result).isNull();
    }
}
