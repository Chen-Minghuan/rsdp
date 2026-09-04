package com.rsdp.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 搭配方案项请求。
 */
@Data
public class SchemeItemRequest {

    /** RSPU ID。 */
    @NotBlank(message = "RSPU ID 不能为空")
    private String rspuId;

    /** 选中的 RSKU ID。 */
    @NotBlank(message = "RSKU ID 不能为空")
    private String rskuId;

    /** 产品数量，至少为 1。 */
    @Min(value = 1, message = "产品数量必须大于等于 1")
    private Integer quantity;

    /** 排序号。 */
    private Integer sortOrder;

    /** 空间覆盖标签（场景字典码，可空=跟随产品场景推导；生成链路如户型图搭配按目标空间回填） */
    private String spaceTag;
}
