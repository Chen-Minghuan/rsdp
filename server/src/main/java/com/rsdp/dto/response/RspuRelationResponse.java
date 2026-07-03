package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * RSPU 产品间关系响应。
 */
@Data
public class RspuRelationResponse {

    private String relationId;
    private String anchorRspuId;
    private String relatedRspuId;
    private String relationType;
    private String reason;
    private Integer sortOrder;
    private String status;

    /**
     * 目标展示产品 ID（搭配关系中的另一端产品）。
     */
    private String targetRspuId;

    /**
     * 目标展示产品名称。
     */
    private String targetDisplayName;

    /**
     * 目标展示产品主图 URL。
     */
    private String targetImageUrl;

    /**
     * 目标展示产品品类路径。
     */
    private String targetCategoryPath;

    /**
     * 目标展示产品最低工厂报价。
     */
    private BigDecimal targetMinPrice;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
