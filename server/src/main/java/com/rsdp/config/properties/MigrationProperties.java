package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 数据迁移配置属性。
 *
 * <p>所有迁移类默认关闭，需显式在配置中启用后重启应用执行。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.migration.encrypt-prices")
public class MigrationProperties {

    /**
     * 是否启用历史价格字段加密迁移。
     */
    private boolean enabled = false;

    /**
     * 每批处理的记录数。
     */
    private int batchSize = 100;
}
