package com.rsdp.dto;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AiLabels {
    private String style;
    private Map<String, String> sixDimTags;
    private String colorPrimaryName;
    private List<Double> colorPrimaryHsv;
    private List<String> materialTags;
    private List<String> sceneTags;
    private String confidence;

    /**
     * OCR 文字识别结果：型号、尺寸、材质、价格、工厂名等。
     */
    private OcrResult ocr;
}
