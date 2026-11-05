package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 内嵌图片列提示（映射确认页用）：该列表头 + 锚定图片数量。
 * 让录入人明确"这列的图片系统会自动按行提取，无需映射为 URL 字段"。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageColumnHint {

    /** 列头（合并表头；无表头时为「第 N 列」）。 */
    private String header;

    /** 该列锚定的内嵌图片数量（含 WPS 标准锚点；WPS DISPIMG 单元格图片不计）。 */
    private int imageCount;
}
