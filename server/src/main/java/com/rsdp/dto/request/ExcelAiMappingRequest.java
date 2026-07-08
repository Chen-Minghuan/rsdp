package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

/**
 * Excel AI 辅助导入：确认字段映射并执行导入的请求。
 */
@Data
public class ExcelAiMappingRequest {

    /**
     * 预览接口返回的批次号。
     */
    @NotBlank
    private String batchId;

    /**
     * 用户确认后的字段映射：原始表头 → 系统字段。
     */
    @NotNull
    private Map<String, String> mapping = new HashMap<>();

    /**
     * 当外部编码已存在时是否更新；false 则跳过。
     */
    private boolean updateIfExists;

    /**
     * 品类提示，当 Excel 中无品类字段时使用。
     */
    private String categoryHint;
}
