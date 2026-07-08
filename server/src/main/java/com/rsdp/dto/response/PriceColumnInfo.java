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
}
