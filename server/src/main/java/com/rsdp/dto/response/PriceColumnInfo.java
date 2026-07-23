package com.rsdp.dto.response;

import lombok.Data;

/**
 * Excel AI 导入中识别出的价格列信息。
 *
 * <p>对应多行表头合并后的价格列，如「价格-A级布」。</p>
 */
@Data
public class PriceColumnInfo {

    /**
     * 原始合并表头，如「价格-A级布」。
     */
    private String header;

    /**
     * 材质名称，从价格列中提取，如「A级布」。
     */
    private String materialName;

    /**
     * AI 建议的映射字段，如「__PRICE__:A级布」。
     */
    private String suggestedField;

    /**
     * 价格列角色："factory"（出厂价，建变体 + RSKU）| "sales"（销售价，写 RSPU 零售参考价）。
     *
     * <p>按表头关键词识别：出厂价/工厂价/EXW → factory；销售价/含税价/零售价/市场价 → sales；
     * 其余默认 factory。历史批次（无该字段）反序列化为 null，消费侧按 factory 处理。</p>
     */
    private String role;
}
