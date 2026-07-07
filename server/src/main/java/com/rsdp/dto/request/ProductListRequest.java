package com.rsdp.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 产品列表查询请求。
 */
@Data
public class ProductListRequest {

    private Long page = 1L;

    @Min(value = 1, message = "每页数量不能小于 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Long size = 10L;
    private String categoryCode;
    private String positioningLabel;
    private String sceneCode;
    private String materialTag;
    private String status;
    private String reviewStatus;
    private String productLevel;
    private String keyword;
    private String viewMode;
    private String factoryCode;
}
