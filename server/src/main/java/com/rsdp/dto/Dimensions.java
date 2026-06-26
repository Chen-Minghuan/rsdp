package com.rsdp.dto;

import lombok.Data;

/**
 * OCR 提取的产品尺寸信息。
 */
@Data
public class Dimensions {

    /**
     * 宽度
     */
    private Integer w;

    /**
     * 深度
     */
    private Integer d;

    /**
     * 高度
     */
    private Integer h;

    /**
     * 尺寸单位：mm / cm / m / inch
     */
    private String unit;
}
