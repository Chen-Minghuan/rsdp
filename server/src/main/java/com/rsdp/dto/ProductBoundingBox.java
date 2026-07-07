package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PDF 页面中单个产品的相对位置框。
 *
 * <p>所有坐标均为相对于页面宽高的比例值（0.0 ~ 1.0），与页面尺寸无关。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ProductBoundingBox {

    /**
     * 左上角 x 坐标（相对页面宽度）。
     */
    private double x;

    /**
     * 左上角 y 坐标（相对页面高度）。
     */
    private double y;

    /**
     * 宽度（相对页面宽度）。
     */
    private double width;

    /**
     * 高度（相对页面高度）。
     */
    private double height;

    /**
     * 校验 bbox 是否在合法范围内且面积大于 0。
     *
     * @return 是否有效
     */
    public boolean isValid() {
        return x >= 0.0 && y >= 0.0
            && width > 0.0 && height > 0.0
            && x + width <= 1.0 + 1e-6
            && y + height <= 1.0 + 1e-6;
    }
}
