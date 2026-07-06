package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品集详情响应。
 */
@Data
public class ProductCollectionResponse {

    private String collectionId;
    private String collectionCode;
    private String name;
    private String description;
    private List<String> categoryCodes;
    private List<String> styleCodes;
    private List<String> targetSegments;
    private Boolean isFeatured;
    private Integer sortOrder;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<ProductCollectionItemResponse> items;
    private Integer itemCount;
}
