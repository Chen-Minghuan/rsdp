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
    private String dimensions;
    private String colorCode;
    private String materialCode;
    private List<String> materialMix;
    private String referencePriceBand;
    private String productLevel;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
