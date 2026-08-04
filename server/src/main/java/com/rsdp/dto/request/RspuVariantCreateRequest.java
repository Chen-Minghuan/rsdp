package com.rsdp.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * RSPU 变体创建请求。
 */
@Data
public class RspuVariantCreateRequest {

    @NotBlank(message = "变体显示名称不能为空")
    private String displayName;

    private String variantCode;

    private String sizeCode;

    /** 尺寸/规格原文（工厂方言，码解析不出时保留） */
    private String sizeText;

    /**
     * 具体尺寸 JSON，例如 {"w":560,"d":580,"h":780,"unit":"mm"}
     */
    private String dimensions;

    private String colorCode;

    /** 颜色原文（工厂方言） */
    private String colorText;

    /**
     * 主材质码（可空：码或材质原文至少提供一个，见 materialText）
     */
    private String materialCode;

    /** 材质原文（工厂方言，码解析不出时保留） */
    private String materialText;

    /**
     * 多种材质组合，例如 ["实木框架", "布艺座包"]
     */
    private List<String> materialMix;

    private String referencePriceBand;

    /** 产品等级覆盖，为空时继承 RSPU。 */
    private String productLevel;
}
