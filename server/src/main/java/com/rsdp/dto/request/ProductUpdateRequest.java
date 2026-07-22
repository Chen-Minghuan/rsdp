package com.rsdp.dto.request;

import lombok.Data;

import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 产品元数据更新请求。
 *
 * <p>所有字段均为可选，{@code null} 表示不更新该字段。
 */
@Data
public class ProductUpdateRequest {

    /** 定位标签/风格字典码，如 MC。 */
    private String positioningLabel;

    /** 产品名称。 */
    @Size(max = 256)
    private String productName;

    /** 风格字典码列表（多风格），第一个为主风格；提供时优先于 positioningLabel。 */
    private List<String> styleCodes;

    /** 主色名。 */
    private String colorPrimaryName;

    /** 主色 HSV，如 [30, 60, 80]。 */
    private List<Double> colorPrimaryHsv;

    /** 材质标签字典码列表。 */
    private List<String> materialTags;

    /** 场景标签字典码列表。 */
    private List<String> sceneTags;

    /** 六维标签。 */
    private Map<String, String> sixDimTags;

    /** 参考价格带。 */
    private String referencePriceBand;

    /** 产品等级，如 S/A/B/C。 */
    private String productLevel;

    /** 保修年限。 */
    private Integer warrantyYears;

    /** 关键规格。 */
    private Map<String, String> keySpecs;
}
