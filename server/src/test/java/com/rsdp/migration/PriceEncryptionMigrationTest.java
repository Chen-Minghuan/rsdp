package com.rsdp.migration;

import com.rsdp.config.properties.MigrationProperties;
import com.rsdp.util.AesEncryptionUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.crypto.spec.SecretKeySpec;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PriceEncryptionMigration} 单元测试。
 */
@ExtendWith(MockitoExtension.class)
class PriceEncryptionMigrationTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private MigrationProperties properties;

    private PriceEncryptionMigration migration;

    @BeforeAll
    static void initKey() throws Exception {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        SecretKeySpec secretKey = new SecretKeySpec(key, "AES");
        Field field = AesEncryptionUtil.class.getDeclaredField("secretKey");
        field.setAccessible(true);
        field.set(null, secretKey);
    }

    @BeforeEach
    void setUp() {
        migration = new PriceEncryptionMigration(properties, jdbcTemplate);
    }

    @Test
    void run_whenDisabled_shouldNotQuery() {
        when(properties.isEnabled()).thenReturn(false);

        migration.run();

        verify(jdbcTemplate, never()).queryForList(anyString());
    }

    @Test
    void run_shouldEncryptPlainPricesAndSkipEncrypted() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);

        String encryptedPrice = AesEncryptionUtil.encrypt(new BigDecimal("999.00"));

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(
                Map.of("rsku_id", "R1", "factory_price", "1234.56"),
                Map.of("rsku_id", "R2", "factory_price", encryptedPrice)
            ))
            .thenReturn(List.of())
            .thenReturn(List.of())
            .thenReturn(List.of());

        migration.run();

        ArgumentCaptor<String> priceCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            eq("UPDATE rsku_supply SET factory_price = ? WHERE rsku_id = ?"),
            priceCaptor.capture(),
            eq("R1")
        );

        String encrypted = priceCaptor.getValue();
        assertThat(encrypted).isNotEqualTo("1234.56");
        assertThat(AesEncryptionUtil.decrypt(encrypted)).isEqualByComparingTo("1234.56");

        verify(jdbcTemplate, never()).update(
            eq("UPDATE rsku_supply SET factory_price = ? WHERE rsku_id = ?"),
            any(),
            eq("R2")
        );
    }

    @Test
    void run_shouldSkipInvalidPrices() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of(
                Map.of("rsku_id", "R3", "factory_price", "not-a-number")
            ))
            .thenReturn(List.of())
            .thenReturn(List.of())
            .thenReturn(List.of());

        migration.run();

        verify(jdbcTemplate, never()).update(eq("UPDATE rsku_supply SET factory_price = ? WHERE rsku_id = ?"), any(), any());
    }

    @Test
    void run_shouldEncryptSchemeItemPlainPrices() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of())
            .thenReturn(List.of(
                Map.of("scheme_item_id", 1L, "factory_price", "888.88")
            ))
            .thenReturn(List.of());

        migration.run();

        ArgumentCaptor<String> priceCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            eq("UPDATE scheme_item SET factory_price = ? WHERE scheme_item_id = ?"),
            priceCaptor.capture(),
            eq(1L)
        );

        assertThat(AesEncryptionUtil.decrypt(priceCaptor.getValue())).isEqualByComparingTo("888.88");
    }

    @Test
    void run_shouldEncryptDesignOrderItemPrices() {
        when(properties.isEnabled()).thenReturn(true);
        when(properties.getBatchSize()).thenReturn(100);

        when(jdbcTemplate.queryForList(anyString()))
            .thenReturn(List.of())  // rsku_supply
            .thenReturn(List.of())  // scheme_item
            .thenReturn(List.of(
                Map.of("id", 5L, "original_price", "666.66")
            ))
            .thenReturn(List.of())  // design_order_item.original_price 下一页
            .thenReturn(List.of(
                Map.of("id", 5L, "final_price", "599.99")
            ))
            .thenReturn(List.of()); // design_order_item.final_price 下一页

        migration.run();

        ArgumentCaptor<String> originalCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            eq("UPDATE design_order_item SET original_price = ? WHERE id = ?"),
            originalCaptor.capture(),
            eq(5L)
        );
        assertThat(AesEncryptionUtil.decrypt(originalCaptor.getValue())).isEqualByComparingTo("666.66");

        ArgumentCaptor<String> finalCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).update(
            eq("UPDATE design_order_item SET final_price = ? WHERE id = ?"),
            finalCaptor.capture(),
            eq(5L)
        );
        assertThat(AesEncryptionUtil.decrypt(finalCaptor.getValue())).isEqualByComparingTo("599.99");
    }
}
