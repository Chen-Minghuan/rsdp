package com.rsdp.migration;

import com.rsdp.config.properties.DataFixProperties;
import com.rsdp.service.RspuPriceSummaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>启动期数据修正任务：由 DatabaseMigrationRunner 在应用启动后按序调用一次；
 * 幂等可安全重入（已处理记录自动跳过）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceSummaryBackfillMigration {

    private final DataFixProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final RspuPriceSummaryService rspuPriceSummaryService;





    /** 执行 RSPU 价格投影回填（幂等重算）。 */
    public void execute() {
        log.info("开始 RSPU 价格投影回填，每批 {} 条", properties.getPriceSummaryBatchSize());
        int offset = 0;
        int total = 0;
        int failed = 0;
        int batchSize = properties.getPriceSummaryBatchSize();

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
