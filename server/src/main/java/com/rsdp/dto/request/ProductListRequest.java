package com.rsdp.dto.request;

import lombok.Data;

/**
 * 产品列表查询请求。
 */
@Data
public class ProductListRequest {

    private Long page = 1L;
    private Long size = 10L;
    private String categoryCode;
    private String positioningLabel;
    private String sceneCode;
    private String materialTag;
    private String status;
    private String reviewStatus;
    private String keyword;
}
