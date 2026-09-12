package com.rsdp.migration;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * 启动时按序执行一次各幂等数据修正任务（加密 → 投影重算 → 标签归一 → 权限补授 → RSPU 录入人回填）。
 * 结构演进由 Flyway 负责，与本类无关。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigrationRunner implements ApplicationRunner, Ordered {

    private final PriceEncryptionMigration priceEncryptionMigration;
    private final PriceSummaryBackfillMigration priceSummaryBackfillMigration;
    private final SixDimNormalizationMigration sixDimNormalizationMigration;
    private final FactoryAdminImportPermissionMigration factoryAdminImportPermissionMigration;
    private final RspuCreatedByBackfillMigration rspuCreatedByBackfillMigration;

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("启动期数据修正任务开始（幂等，已处理自动跳过）");
        priceEncryptionMigration.execute();
        priceSummaryBackfillMigration.execute();
        sixDimNormalizationMigration.execute();
        factoryAdminImportPermissionMigration.execute();
        rspuCreatedByBackfillMigration.execute();
        log.info("启动期数据修正任务完成");
    }
}
