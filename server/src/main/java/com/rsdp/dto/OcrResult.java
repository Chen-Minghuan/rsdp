package com.rsdp.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * OCR 文字识别结构化结果。
 * 用于把图片中的文字信息（型号、尺寸、材质、价格等）映射到业务表字段。
 */
@Data
public class OcrResult {

    /**
     * 图片中识别到的原始文字，按行保留
     */
    private String rawText;

    /**
     * 产品名称
     */
    private String productName;

    /**
     * 型号 / 款号
     */
    private String modelNumber;

    /**
     * 品牌名
     */
    private String brand;

    /**
     * 工厂 / 厂家名
     */
    private String factoryName;

    /**
     * 原始尺寸文字，如 "560*580*780mm"
     */
    private String dimensionText;

    /**
     * 结构化尺寸
     */
    private Dimensions dimensions;

    /**
     * 材质说明原文
     */
    private String materialDescription;

    /**
     * 颜色文字
     */
    private String colorText;

    /**
     * 价格文字
     */
    private String priceText;

    /**
     * 结构化价格（仅当图片来自报价单/工厂图时可靠）
     */
    private BigDecimal price;

    /**
     * 货币代码，默认 CNY
     */
    private String currency;

    /**
     * 其他信息：质保、MOQ、交期、净重、包装尺寸、备注等
     */
    private Map<String, Object> otherInfo;
}
