package com.rsdp.migration;

import com.rsdp.config.properties.MigrationProperties;
import com.rsdp.util.AesEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 历史价格字段加密迁移。
 *
 * <p>将 {@code rsku_supply.factory_price} 与 {@code scheme_item.factory_price}
 * 中的明文价格批量加密为 AES-256-GCM Base64 密文。已加密记录会自动跳过，支持幂等执行。</p>
 *
 * <p>通过 {@code rsdp.migration.encrypt-prices.enabled=true} 开启，默认关闭。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceEncryptionMigration implements CommandLineRunner {

    private final MigrationProperties properties;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            log.info("历史价格加密迁移已禁用（rsdp.migration.encrypt-prices.enabled=false）");
            return;
        }

        log.info("开始历史价格加密迁移，每批 {} 条", properties.getBatchSize());
        migrateRskuSupply();
        migrateSchemeItem();
        log.info("历史价格加密迁移完成");
    }

    private void migrateRskuSupply() {
        String selectSql = "SELECT rsku_id, factory_price FROM rsku_supply "
            + "WHERE factory_price IS NOT NULL AND deleted_at IS NULL";
        migrateTable("rsku_supply", "rsku_id", selectSql);
    }

    private void migrateSchemeItem() {
        String selectSql = "SELECT scheme_item_id, factory_price FROM scheme_item "
            + "WHERE factory_price IS NOT NULL";
        migrateTable("scheme_item", "scheme_item_id", selectSql);
    }

    private void migrateTable(String tableName, String idColumn, String selectSql) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(selectSql);
        if (rows.isEmpty()) {
            log.info("表 {} 无需迁移的价格记录", tableName);
            return;
        }

        int total = rows.size();
        int alreadyEncrypted = 0;
        int migrated = 0;
        int failed = 0;

        log.info("表 {} 发现 {} 条待处理价格记录", tableName, total);

        for (Map<String, Object> row : rows) {
            Object idValue = row.get(idColumn);
            String rawPrice = (String) row.get("factory_price");

            if (rawPrice == null || rawPrice.isBlank()) {
                continue;
            }

            // 幂等：已加密则跳过
            if (isEncrypted(rawPrice)) {
                alreadyEncrypted++;
                continue;
            }

            BigDecimal plainPrice;
            try {
                plainPrice = new BigDecimal(rawPrice.trim());
            } catch (NumberFormatException e) {
                failed++;
                log.error("表 {} 记录 {} 的价格无法解析为数字：{}", tableName, idValue, rawPrice);
                continue;
            }

            String encrypted = AesEncryptionUtil.encrypt(plainPrice);
            String updateSql = String.format("UPDATE %s SET factory_price = ? WHERE %s = ?", tableName, idColumn);
            try {
                jdbcTemplate.update(updateSql, encrypted, idValue);
                migrated++;
            } catch (Exception e) {
                failed++;
                log.error("表 {} 记录 {} 加密更新失败", tableName, idValue, e);
            }
        }

        log.info("表 {} 迁移结果：总计 {}，已加密跳过 {}，本次迁移 {}，失败 {}",
            tableName, total, alreadyEncrypted, migrated, failed);
    }

    /**
     * 判断价格字符串是否已经是 AES 密文。
     *
     * <p>先进行 Base64 格式与最小长度预筛，再通过解密验证，避免明文数字触发大量错误日志。</p>
     */
    private boolean isEncrypted(String value) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
        // AES-256-GCM: IV(12) + ciphertext(at least 1) + tag(16)
        if (decoded.length < 29) {
            return false;
        }
        try {
            AesEncryptionUtil.decrypt(value);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
