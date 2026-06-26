package com.rsdp.dto.response;

import lombok.Data;

import java.util.List;

/**
 * 报价单响应。
 */
@Data
public class QuoteResponse {

    private List<QuoteItemResponse> items;
    private QuoteSummaryResponse summary;
}
