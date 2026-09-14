package com.rsdp.dto.response;

/**
 * Excel AI 导入可映射标准字段选项（前端映射下拉用）。
 *
 * @param label 展示文案（字段中文名 + 字段名）
 * @param value 标准字段名；空串表示「不映射」
 */
public record StandardFieldOption(String label, String value) {
}
