package com.rsdp.agent.domain;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 营销 Agent 产品检索条件。
 *
 * <p>由 Tool 层从需求约束（RequirementConstraints）翻译而来，全部字段可空，
 * 空字段不参与过滤。{@code topN} 为空时使用 {@code rsdp.marketing-agent.search-top-n}。</p>
 */
@Data
public class ProductSearchCriteria {

    /** 品类编码（category_dict dict_type=category 的 dict_code）。 */
    private String categoryCode;

    /** 风格（字典码或工厂方言叫法，进入查询前经 DictAliasService 归一）。 */
    private String style;

    /** 材质（字典码或工厂方言叫法，进入查询前经 DictAliasService 归一）。 */
    private String material;

    /** 颜色（字典码或自然语言颜色名；color 字典缺失时按 color_primary_name 文本模糊匹配）。 */
    private String color;

    /** 预算上限（零售参考价 ≤ 该值）。 */
    private BigDecimal budgetMax;

    /** 最大宽度（mm），尺寸硬过滤。 */
    private Integer maxWidthMm;

    /** 最小宽度（mm），尺寸硬过滤。 */
    private Integer minWidthMm;

    /** 关键词（product_name / positioning_label 模糊匹配）。 */
    private String keyword;

    /** 返回条数上限；null 时用 properties.searchTopN。 */
    private Integer topN;
}
