package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 存量六维标签归一迁移配置属性。
 *
 * <p>默认关闭，需显式在配置中启用后重启应用执行（参照历史价格加密迁移模式）。</p>
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.migration.six-dim-normalization")
public class SixDimNormalizationProperties {

    /**
     * 是否启用存量六维标签归一迁移。
     */
    private boolean enabled = false;

    /**
     * 每批处理的记录数。
     */
    private int batchSize = 200;

    /**
     * 对账报表（未命中清单）输出目录，相对工作目录。
     */
    private String reportDir = "data/reports";
}
