package com.rsdp.dto.request;

import lombok.Data;

/**
 * Excel AI 导入「导入前全量预览」中的单元格编辑项。
 *
 * <p>以原始表头为定位键，后端直接应用到原始数据行，避免复合映射列歧义。</p>
 */
@Data
public class PreviewEdit {

    /**
     * Excel 物理行号（1-based），与 {@link com.rsdp.dto.response.PreviewDataRow#getRowIndex()} 一致。
     */
    private int rowIndex;

    /**
     * 原始表头。
     */
    private String header;

    /**
     * 修改后的单元格值。
     */
    private String value;
}
