package com.rsdp.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel AI 导入「导入前全量预览」中的商品组。
 *
 * <p>按 externalCode（型号）聚合同一商品的多个尺寸/价格变体行。
 * 商品级字段（型号、品类、名称、描述、图片等）在组内共享；
 * 变体级字段（尺寸、价格等）放在 {@link PreviewDataVariant} 中。</p>
 */
@Data
public class PreviewDataGroup {

    /**
     * 商品外部编码（型号），作为分组键。
     */
    private String externalCode;

    /**
     * 商品级原始表头 → 单元格值。
     * 取组内第一行的值（已做过 forward fill）。
     */
    private Map<String, String> productRawValues = new LinkedHashMap<>();

    /**
     * 商品级原始表头 → 系统字段。
     */
    private Map<String, String> productMappedFieldByHeader = new LinkedHashMap<>();

    /**
     * 商品图片（取组内第一行）。
     */
    private List<PreviewDataRow.PreviewRowImage> images = new ArrayList<>();

    /**
     * 用户在数据清洗页覆盖到该商品组的图片 asset ID 列表。
     */
    private List<String> overrideImageAssetIds = new ArrayList<>();

    /**
     * 该商品下的全部变体行。
     */
    private List<PreviewDataVariant> variants = new ArrayList<>();

    /**
     * 组内代表行的 Excel 物理行号（1-based），用于错误提示与失败明细。
     */
    private int representativeRowIndex;
}
