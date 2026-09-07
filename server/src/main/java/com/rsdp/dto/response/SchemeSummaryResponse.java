package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 搭配方案列表项响应。
 */
@Data
public class SchemeSummaryResponse {

    private String schemeId;
    private String schemeName;
    private Integer itemCount;
    /** 成本口径总价（scheme.total_price 原值）：仅平台员工可见，其他角色为 null；前端应使用 totalSalePrice。 */
    private BigDecimal totalPrice;
    /** 销售价合计（Σ标准售价×数量，响应层实时换算，全角色可见；未定价项跳过求和）。 */
    private BigDecimal totalSalePrice;
    private String createdBy;
    private LocalDateTime createdAt;
    private Boolean isTemplate;
    private List<String> templateTags;
}
