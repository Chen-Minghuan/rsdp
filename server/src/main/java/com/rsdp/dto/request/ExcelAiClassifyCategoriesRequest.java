package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Excel AI 导入：行级品类预分类请求（数据清洗页进入时调用）。
 *
 * <p>候选集约束的 AI（品类归一 fallback + 逐行分类）推迟到候选品类确定之后执行，
 * 由本接口承载；接口幂等（纯计算，不写库），前端进入步骤 3 时可重复触发。</p>
 */
@Data
public class ExcelAiClassifyCategoriesRequest {

    /**
     * 导入方式：SINGLE 只做确定性归一 + 默认品类兜底（不调 AI）；
     * MIXED 在候选集约束下做 AI 逐行分类。
     */
    @NotNull
    private CategoryMode mode;

    /**
     * SINGLE 的默认品类（与导入请求的 categoryHint 同一字段）。
     */
    private String categoryHint;

    /**
     * MIXED 候选品类码列表（≥2 个），AI 输出必须落在该集合内，否则按未识别处理。
     */
    private List<String> candidateCategoryCodes;

    /**
     * 当前工作表名（仅作为 AI 分类的上下文线索注入 prompt，不作直接品类来源）。
     */
    private String sheetName;

    /**
     * 用户确认后的字段映射（原始表头 → 系统字段）；缺省时回退批次预览时保存的映射。
     */
    private Map<String, String> mapping;

    /**
     * 用户确认的品类映射（品类原始值 → 字典码）；行内类别列确定性归一的最高优先级。
     */
    private Map<String, String> categoryMapping;
}
