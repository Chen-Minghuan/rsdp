package com.rsdp.dto.response;

import java.util.Map;

/**
 * 六维标签维度定义响应（品类 × A-F 维度键 → 标签/说明）。
 *
 * @param categoryCode 品类码
 * @param categoryName 品类中文名（字典未收录时回退为品类码）
 * @param dims         维度键 → 维度定义
 */
public record SixDimSchemaResponse(
    String categoryCode,
    String categoryName,
    Map<String, DimDefinition> dims
) {

    /**
     * 单维度定义。
     *
     * @param label       维度标签
     * @param description 维度说明（取值范围提示）
     */
    public record DimDefinition(String label, String description) {
    }
}
