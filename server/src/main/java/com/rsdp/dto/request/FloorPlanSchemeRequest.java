package com.rsdp.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
}
