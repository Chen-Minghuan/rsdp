package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
     * SINGLE 导入方式下即「默认商品品类」（同一字段，DB 复用 category_hint 列）：
     * 行内类别列确定性归一命中时以行内值为准，本字段只补空值行。
     */
    private String categoryHint;

    /**
     * 导入方式（SINGLE 单一品类 / MIXED 混合品类），按 Sheet（批次）生效。
     * 缺省（null）表示未选导入方式的旧路径：维持原有品类兜底链
     * （行类别列 → Sheet 名归一 → 品类提示 → categoryGuess），向后兼容。
     */
    private CategoryMode categoryMode;

    /**
     * MIXED 候选品类码列表（≥2 个），限定 AI 逐行分类的识别范围；随导入请求提交。
     */
    private List<String> candidateCategoryCodes;

    /**
     * 数据清洗后的行级最终品类：Excel 物理行号（1-based）→ 品类字典码。
     * 新模式（categoryMode 非空）下后端只认本集合（与 previewEdits / skipRows 同模式）；
     * AI/系统的 suggested 建议仅前端展示，不提交。
     */
    private Map<Integer, String> rowCategorySelections;

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
     * 默认产品等级（factory_level 字典码或名称），行内无产品等级列时作为兜底，
     * 与 defaultFactoryCode/defaultMoq 同属请求级默认值；
     * 非法值记行级问题（rowIssue）不阻断导入，本行产品等级留空。
     */
    @Size(max = 16)
    private String defaultProductLevel;

    /**
     * 默认材质码（material 字典码或名称），价格列材质名与行级材质均无法归一时作为兜底
     * （如单列「出厂价」且数据无材质列的场景），与 defaultProductLevel 同属请求级默认值；
     * 非法值记行级问题（rowIssue）不阻断导入，本行按原文保留。
     */
    @Size(max = 32)
    private String defaultMaterialCode;

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
