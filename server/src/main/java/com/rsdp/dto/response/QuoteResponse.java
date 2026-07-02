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

    /**
     * 价格变动提示。
     * 当基于已有方案重新生成报价单时，若 RSKU 当前价格与方案保存时的快照不一致，
     * 会在此列出所有变动项。
     */
    private List<PriceChangeResponse> priceChanges;
}
