package com.rsdp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiLabels {
    private String style;
    /**
     * 备选风格（模型判断产品也可能适用的其他风格，不含主风格）。
     */
    private List<String> secondaryStyles;
    private Map<String, String> sixDimTags;
    private String colorPrimaryName;
    private List<Double> colorPrimaryHsv;
    private List<String> materialTags;
    /**
     * 面料标签（沙发/床垫/椅子等软体商品的接触面面料，如 亚麻/科技布；与结构材质区分）。
     */
    private List<String> fabricTags;
    private List<String> sceneTags;
    private String confidence;

    /**
     * OCR 文字识别结果：型号、尺寸、材质、价格、工厂名等。
     */
    private OcrResult ocr;
}
