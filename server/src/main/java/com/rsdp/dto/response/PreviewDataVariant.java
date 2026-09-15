package com.rsdp.dto.response;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Excel AI 导入「导入前全量预览」中的商品变体行。
 *
 * <p>同一商品（型号）下，不同尺寸/价格/颜色等变体对应 Excel 中的一行。
 * 变体级字段保留原始表头视角，便于在数据清洗页按原始列编辑。</p>
 */
@Data
public class PreviewDataVariant {

    /**
     * Excel 物理行号（1-based），与导入失败明细中的 rowIndex 口径一致。
     */
    private int rowIndex;

    /**
     * 变体级原始表头 → 单元格值。
     */
    private Map<String, String> rawValues = new LinkedHashMap<>();

    /**
     * 变体级原始表头 → 系统字段。
     */
    private Map<String, String> mappedFieldByHeader = new LinkedHashMap<>();
}
