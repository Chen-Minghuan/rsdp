package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel AI 辅助导入的字段映射预览响应。
 */
@Data
public class ExcelAiMappingResponse {

    /**
     * 导入批次号。
     */
    private String batchId;

    /**
     * 原始表头列表（按列顺序）。
     */
    private List<String> headers = new ArrayList<>();

    /**
     * AI 建议的字段映射：原始表头 → 系统字段。
     */
    private Map<String, String> suggestedMapping = new HashMap<>();

    /**
     * 前 N 行样例数据，key 为原始表头。
     */
    private List<Map<String, String>> previewRows = new ArrayList<>();

    /**
     * AI 对本次映射的说明。
     */
    private String notes;
}
