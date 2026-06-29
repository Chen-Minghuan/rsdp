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

    /**
     * 具体尺寸 JSON，例如 {"w":560,"d":580,"h":780,"unit":"mm"}
     */
    private String dimensions;

    private String colorCode;

    @NotBlank(message = "主材质码不能为空")
    private String materialCode;

    /**
     * 多种材质组合，例如 ["实木框架", "布艺座包"]
     */
    private List<String> materialMix;

    private String referencePriceBand;

    /** 产品等级覆盖，为空时继承 RSPU。 */
    private String productLevel;
}
