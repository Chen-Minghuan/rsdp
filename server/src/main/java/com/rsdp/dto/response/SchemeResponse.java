package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 搭配方案详情响应。
 */
@Data
public class SchemeResponse {

    private String schemeId;
    private String schemeName;
    private String roomType;
    private BigDecimal budgetLimit;
    private BigDecimal totalPrice;
    private Integer factoryCount;
    private Integer maxLeadTimeDays;
    private Integer itemCount;
    private String status;
    private LocalDateTime createdAt;
    private List<SchemeItemResponse> items;
}
