package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    /**
     * 默认工厂编码，用于为每个价格列创建 RSKU。
     */
    private String defaultFactoryCode;

    /**
     * 默认发货地，用于为每个价格列创建 RSKU。
     */
    private String defaultShippingFrom;

    /**
     * 默认最小起订量，用于为每个价格列创建 RSKU。
     */
    private Integer defaultMoq;

    /**
     * 用户确认要导入的价格列原始表头列表。
     * 为空表示全部导入。
     */
    private List<String> selectedPriceColumns = new ArrayList<>();
}
