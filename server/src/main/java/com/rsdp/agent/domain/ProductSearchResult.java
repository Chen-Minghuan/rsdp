package com.rsdp.agent.domain;

import lombok.Data;

import java.util.List;

/**
 * 营销 Agent 产品检索结果。
 */
@Data
public class ProductSearchResult {

    /** 命中项（已按 topN 截断）。 */
    private List<ProductSearchItem> items;

    /** 截断前的命中总数（含尺寸硬过滤后的真实总数）。 */
    private int totalMatched;

    /** 本次检索是否使用了向量召回通道（P2，批次留痕用）。 */
    private boolean vectorChannelUsed;
}
