package com.rsdp.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 户型图空间框（归一化坐标 [0,1]，x/y 为左上角，w/h 为宽高）。
 *
 * <p>请求（人工校正提交）与响应（空间明细）共用的唯一 bbox 类型。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FloorPlanBBox {

    @DecimalMin(value = "0.0", message = "bbox 坐标必须在 [0,1] 区间")
    @DecimalMax(value = "1.0", message = "bbox 坐标必须在 [0,1] 区间")
    private Double x;

    @DecimalMin(value = "0.0", message = "bbox 坐标必须在 [0,1] 区间")
    @DecimalMax(value = "1.0", message = "bbox 坐标必须在 [0,1] 区间")
    private Double y;

    @DecimalMin(value = "0.0", message = "bbox 坐标必须在 [0,1] 区间")
    @DecimalMax(value = "1.0", message = "bbox 坐标必须在 [0,1] 区间")
    private Double w;

    @DecimalMin(value = "0.0", message = "bbox 坐标必须在 [0,1] 区间")
    @DecimalMax(value = "1.0", message = "bbox 坐标必须在 [0,1] 区间")
    private Double h;
}
