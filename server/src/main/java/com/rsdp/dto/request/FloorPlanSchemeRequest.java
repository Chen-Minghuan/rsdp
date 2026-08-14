package com.rsdp.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 户型图搭配方案生成请求（管理端接口 4，户型图链路 v3.0 §4.2）。
 */
@Data
public class FloorPlanSchemeRequest {

    /** 目标空间 ID（必须为该分析批次下的已确认空间）。 */
    @NotBlank(message = "空间 ID 不能为空")
    private String roomId;

    /** 风格偏好（风格字典码），可空。 */
    private String stylePreference;

    /** 预算上限（元），可空。 */
    @Min(value = 0, message = "预算上限不能为负数")
    private BigDecimal budgetLimit;

    /** 所属设计项目 ID，可空。 */
    private String projectId;

    /**
     * 沙发墙朝向（户型图链路 v3.0 §8 P1）：width=开间方向墙（默认，缺省/null 按 width 处理）、
     * depth=进深方向墙。影响 R2 沙发/电视柜长度上限的墙长取值与 R3 链式校验方向。
     */
    @Pattern(regexp = "width|depth", message = "沙发墙朝向仅支持 width（开间方向墙）或 depth（进深方向墙）")
    private String sofaWall;
}
