package com.rsdp.dto.response;

import lombok.Data;

/**
 * 产品集项响应。
 */
@Data
public class ProductCollectionItemResponse {

    private Long id;
    private String rspuId;
    private String rspuName;
    private String primaryImageUrl;
    private Integer sortOrder;
}
