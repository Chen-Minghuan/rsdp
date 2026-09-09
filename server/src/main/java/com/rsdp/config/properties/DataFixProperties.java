package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 启动期数据修正任务的执行参数（批次大小、报表目录）。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.data-fix")
public class DataFixProperties {

    /** 历史价格加密：每批记录数。 */
    private int encryptBatchSize = 100;

    /** 价格投影回填：每批 RSPU 数。 */
    private int priceSummaryBatchSize = 100;

    /** 六维标签归一：每批记录数。 */
    private int sixDimBatchSize = 200;

    /** 六维归一对账报表（未命中清单）输出目录，相对工作目录。 */
    private String reportDir = "data/reports";
}
