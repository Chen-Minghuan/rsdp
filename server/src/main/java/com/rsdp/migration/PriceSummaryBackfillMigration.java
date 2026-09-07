package com.rsdp.migration;

import com.rsdp.config.properties.PriceSummaryBackfillProperties;
import com.rsdp.service.RspuPriceSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RSPU 价格投影存量回填迁移。
 *
 * <p>{@code rsku_supply.factory_price} 为 AES 加密列，无法 SQL 回填
 * {@code rspu_price_summary}，须逐 RSPU 走 Java 解密重算（复用
 * {@link RspuPriceSummaryService#recalculate}，与写路径同一口径）。
 * 重算为幂等 upsert，可重复执行；含已软删 RSPU（重算后投影行为 0/NULL，
 * 回收站还原时写路径会再次重算）。</p>
 *
 * <p>通过 {@code rsdp.migration.price-summary-backfill.enabled=true} 开启，
 * 默认关闭（与 migration 包既有约定一致：启用后重启执行一次即可）。
 * 注意：V44 上线后若未执行本回填，产品列表最低出厂价/报价数将显示为空/0，
 * 部署时必须开启一次。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceSummaryBackfillMigration implements CommandLineRunner {

    private final PriceSummaryBackfillProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final RspuPriceSummaryService rspuPriceSummaryService;

    @Override
    public void run(String... args) {
        if (!properties.isEnabled()) {
            log.info("RSPU 价格投影回填已禁用（rsdp.migration.price-summary-backfill.enabled=false）");
            return;
        }

        log.info("开始 RSPU 价格投影回填，每批 {} 条", properties.getBatchSize());
        int offset = 0;
        int total = 0;
        int failed = 0;
        int batchSize = properties.getBatchSize();

        while (true) {
            List<String> rspuIds = jdbcTemplate.queryForList(
                "SELECT rspu_id FROM rspu_master ORDER BY rspu_id LIMIT ? OFFSET ?",
                String.class, batchSize, offset);
            if (rspuIds.isEmpty()) {
                break;
            }
            for (String rspuId : rspuIds) {
                try {
                    rspuPriceSummaryService.recalculate(rspuId);
                    total++;
                } catch (Exception e) {
                    failed++;
                    log.error("价格投影回填失败，rspuId={}", rspuId, e);
                }
            }
            offset += rspuIds.size();
            log.info("价格投影回填进度：已处理 {}，失败 {}", offset, failed);
        }

        log.info("RSPU 价格投影回填完成：总计 {}，失败 {}", total, failed);
    }
}
