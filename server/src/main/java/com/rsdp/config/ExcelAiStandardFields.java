package com.rsdp.config;

import com.rsdp.dto.response.StandardFieldOption;

import java.util.List;

/**
 * Excel AI 导入可映射标准字段清单（随 preview 响应下发给前端）。
 *
 * <p><b>同步义务</b>：字段真实出处是 {@code ExcelAiImportService.MAPPING_SYSTEM_PROMPT}
 * 中的「标准字段列表」（AI 只能映射到这些字段）。新增/删除可映射字段时必须同步三处：
 * <ol>
 *   <li>{@code ExcelAiImportService.MAPPING_SYSTEM_PROMPT}（AI 映射提示词）</li>
 *   <li>本类 {@link #MAPPABLE_FIELDS}（preview 响应下发）</li>
 *   <li>前端 {@code web/src/views/ProductExcelAiImportView.vue} 的 STANDARD_FIELDS 兜底清单</li>
 * </ol>
 */
public final class ExcelAiStandardFields {

    private ExcelAiStandardFields() {
    }

    /**
     * 可映射标准字段清单（含「不映射」空值项，顺序即前端下拉顺序）。
     */
    public static final List<StandardFieldOption> MAPPABLE_FIELDS = List.of(
        new StandardFieldOption("（不映射）", ""),
        new StandardFieldOption("品类码 (categoryCode)", "categoryCode"),
        new StandardFieldOption("外部编码 (externalCode)", "externalCode"),
        new StandardFieldOption("产品名称 (productName)", "productName"),
        new StandardFieldOption("风格 (positioningLabel)", "positioningLabel"),
        new StandardFieldOption("主色 (colorPrimaryName)", "colorPrimaryName"),
        new StandardFieldOption("材质标签 (materialTags)", "materialTags"),
        new StandardFieldOption("场景标签 (sceneTags)", "sceneTags"),
        new StandardFieldOption("产品等级 (productLevel)", "productLevel"),
        new StandardFieldOption("保修年限 (warrantyYears)", "warrantyYears"),
        new StandardFieldOption("参考价格带 (referencePriceBand)", "referencePriceBand"),
        new StandardFieldOption("六维标签 (sixDimTags)", "sixDimTags"),
        new StandardFieldOption("关键规格 (keySpecs)", "keySpecs"),
        new StandardFieldOption("主图URL (primaryImageUrl)", "primaryImageUrl"),
        new StandardFieldOption("详情图URLs (detailImageUrls)", "detailImageUrls"),
        new StandardFieldOption("变体显示名称 (variantDisplayName)", "variantDisplayName"),
        new StandardFieldOption("尺寸码 (sizeCode)（仅字典码 S/M/L/SINGLE，尺寸数值请选尺寸文字）", "sizeCode"),
        new StandardFieldOption("颜色码 (colorCode)（仅字典码，颜色名请选主色）", "colorCode"),
        new StandardFieldOption("材质码 (materialCode)（仅字典码 WO/PE/FA，材质名请选材质标签）", "materialCode"),
        new StandardFieldOption("尺寸文字 (dimensions)（W*D*H 数值尺寸选这个）", "dimensions"),
        new StandardFieldOption("交期天数 (leadTimeDays)", "leadTimeDays")
    );
}
