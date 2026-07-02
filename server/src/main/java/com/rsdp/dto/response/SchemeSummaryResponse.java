package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 搭配方案列表项响应。
 */
@Data
public class SchemeSummaryResponse {

    private String schemeId;
    private String schemeName;
    private Integer itemCount;
    private BigDecimal totalPrice;
    private LocalDateTime createdAt;
}
