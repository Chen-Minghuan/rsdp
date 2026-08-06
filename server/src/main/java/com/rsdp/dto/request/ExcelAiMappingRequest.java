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
     * 默认发货仓库 ID。
     */
    private String shippingWarehouseId;

    /**
     * 默认发货地，用于为每个价格列创建 RSKU。
     */
    private String defaultShippingFrom;

    /**
     * 默认最小起订量，用于为每个价格列创建 RSKU。
     */
    private Integer defaultMoq;

    /**
     * 默认交期天数，未配置规则时使用。
     */
    private Integer defaultLeadTimeDays;

    /**
     * 导入备注。
     */
    private String importNote;

    /**
     * 用户确认要导入的价格列原始表头列表。
     * 契约：字段缺省/null = 未提供 → 默认全部价格列；显式空数组 [] = 用户明确不选任何价格列。
     * 兼容旧前端：通过该字段选择的价格列全部视为 factory（出厂价）角色。
     */
    private List<String> selectedPriceColumns;

    /**
     * 用户确认要导入的价格列及角色（factory/sales）列表。
     * 契约：与 selectedPriceColumns 同时存在时以本字段为准；本字段为 null 时回退 selectedPriceColumns；
     * 显式空数组 [] = 用户明确不选任何价格列。
     */
    private List<PriceColumnSelection> priceColumnSelections;

    /**
     * 用户确认的品类映射：品类原始值 → 字典码。
     * 行级品类解析时优先级最高；导入完成后写回别名库自学习。
     */
    private Map<String, String> categoryMapping = new HashMap<>();

    /**
     * 用户在「导入前全量预览」中对原始单元格的编辑项。
     * 后端在导入前按 rowIndex + header 定位并覆盖原始数据行对应值。
     */
    private List<PreviewEdit> previewEdits = new ArrayList<>();

    /**
     * 用户在「导入前全量预览」中标记为跳过的 Excel 物理行号（1-based）。
     * 这些行不会进入后续导入流程，也不计入总行数。
     */
    private List<Integer> skipRows = new ArrayList<>();
}
