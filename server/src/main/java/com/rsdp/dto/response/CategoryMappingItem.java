package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel AI 导入：品类原始值 → 字典码的映射建议项。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CategoryMappingItem {

    /**
     * Excel 品类列原始值（如「茶桌」）。
     */
    private String rawValue;

    /**
     * 建议的字典码（如 TB）；无法建议时为 null。
     */
    private String suggestedCode;

    /**
     * 建议来源：dict（字典码/字典名命中）、alias（别名库命中）、ai（AI 归一）、none（无法解析）。
     */
    private String source;
}
