package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * AI 空间搭配方案响应。
 */
@Data
public class RoomSchemeResponse {

    private String roomType;
    private BigDecimal budgetLimit;
    private BigDecimal totalPrice;
    private int itemCount;
    private String reasoning;
    private List<SchemeItemResponse> items;
}
