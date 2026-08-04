package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Excel 工作簿中的单个工作表信息（多 Sheet 导入预览用）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExcelSheetInfo {

    /**
     * 工作表索引（0-based）。
     */
    private int index;

    /**
     * 工作表名称。
     */
    private String name;

    /**
     * 近似数据行数（物理行数上限，含表头/标题行）。
     */
    private int rowCount;
}
