package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Excel AI 导入「导入前全量预览」响应。
 */
@Data
public class ExcelAiPreviewDataResponse {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 总行数。
     */
    private int totalRows;

    /**
     * 原始表头列表（按列顺序）。
     */
    private List<String> headers = new ArrayList<>();

    /**
     * 全量数据行。
     */
    private List<PreviewDataRow> rows = new ArrayList<>();
}
