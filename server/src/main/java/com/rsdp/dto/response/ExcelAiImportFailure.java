package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel AI 辅助导入失败明细。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelAiImportFailure {

    /**
     * Excel 行号（从 2 开始，第 1 行为表头）。
     */
    private Integer rowIndex;

    /**
     * 失败原因。
     */
    private String reason;
}
