package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RSPU 价格投影回填迁移配置属性。
 *
 * <p>与 {@link MigrationProperties} 同约定：默认关闭，需显式在配置中启用后重启应用执行。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.migration.price-summary-backfill")
public class PriceSummaryBackfillProperties {

    /**
     * 是否启用 RSPU 价格投影存量回填。
     */
    private boolean enabled = false;

    /**
     * 每批处理的 RSPU 数。
     */
    private int batchSize = 100;
}
