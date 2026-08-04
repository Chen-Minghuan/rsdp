package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 场景照片中检测到的单个家具产品。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SceneDetectedProduct {

    /**
     * 产品相对位置框（比例坐标 0.0 ~ 1.0）。
     */
    private ProductBoundingBox bbox;

    /**
     * AI 预估的品类码，如 FS / SF / TB。
     */
    private String estimatedCategory;

    /**
     * AI 给出的简短产品名称（如「三人位沙发」），仅作展示参考。
     */
    private String label;
}
