package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

/**
 * 保存方案画布布局请求（搭配画布）。
 *
 * <p>layout 为画布上各方案明细的摆位映射：key = scheme_item_id 字符串，
 * value = 位置/缩放/层级。传 {@code null} 或空 Map 表示清空画布布局。</p>
 */
@Data
public class SaveCanvasLayoutRequest {

    /**
     * 画布布局（可空/空 = 清空画布布局）：
     * {@code { "<schemeItemId>": { "x": 0.12, "y": 0.30, "scale": 1.0, "z": 1 } }}。
     */
    private Map<String, @Valid CanvasPosition> layout;

    /**
     * 画布上单个方案明细的摆位信息。
     */
    @Data
    public static class CanvasPosition {

        /** 相对横坐标（0~1，画布宽占比）。 */
        @NotNull(message = "x 不能为空")
        @DecimalMin(value = "0", message = "x 必须在 0~1 之间")
        @DecimalMax(value = "1", message = "x 必须在 0~1 之间")
        private Double x;

        /** 相对纵坐标（0~1，画布高占比）。 */
        @NotNull(message = "y 不能为空")
        @DecimalMin(value = "0", message = "y 必须在 0~1 之间")
        @DecimalMax(value = "1", message = "y 必须在 0~1 之间")
        private Double y;

        /** 缩放倍率（0.3~3，默认 1.0）。 */
        @NotNull(message = "scale 不能为空")
        @DecimalMin(value = "0.3", message = "scale 必须在 0.3~3 之间")
        @DecimalMax(value = "3", message = "scale 必须在 0.3~3 之间")
        private Double scale;

        /** 层级（z-index，可空，缺省按摆位顺序）。 */
        private Integer z;
    }
}
