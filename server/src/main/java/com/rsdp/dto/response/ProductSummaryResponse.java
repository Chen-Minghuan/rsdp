package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 产品列表项响应。
 */
@Data
public class ProductSummaryResponse {

    private String rspuId;
    private String rspuCode;
    private String categoryCode;
    private String categoryPath;
    private String positioningLabel;
    private String productName;
    private String colorPrimaryName;
    private String status;
    private String aestheticsConfidence;
    private String productLevel;
    private BigDecimal minFactoryPrice;
    /**
     * 是否存在有效出厂报价（v3.3 §42.3/§43.1）：降级场景（单 RSKU 或 min=max）
     * 下 min/max 置 null 时，有成本权限的主体凭此布尔位区分「无报价」与「报价被
     * 防反推规则隐藏」；无成本权限主体为 null（不暴露成本口径）。
     */
    private Boolean hasFactoryQuote;
    /** 零售参考价/销售价（不加密，官网对外展示口径）。 */
    private BigDecimal retailPrice;
    private String primaryImageUrl;
    private List<String> factoryCodes;
    /** RSKU 报价数（工作台「报价×N」chip）。 */
    private Long rskuCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
