package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * RSPU 变体响应。
 */
@Data
public class RspuVariantResponse {

    private String variantId;
    private String rspuId;
    private String displayName;
    private String variantCode;
    private String sizeCode;
    /** 尺寸/规格原文（工厂方言） */
    private String sizeText;
    private String dimensions;
    private String colorCode;
    /** 颜色原文（工厂方言） */
    private String colorText;
    private String materialCode;
    /** 材质原文（工厂方言） */
    private String materialText;
    private List<String> materialMix;
    private String referencePriceBand;
    private String productLevel;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
