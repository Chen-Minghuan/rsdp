package com.rsdp.agent.domain;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 营销 Agent 检索命中项（证据卡片原料）。
 *
 * <p>{@code matchedConditions} 记录本次命中的过滤条件描述（如「品类=沙发」「预算≤20000」），
 * 供 Agent 组装推荐 evidence，避免模型自行编造命中理由。</p>
 */
@Data
public class ProductSearchItem {

    private String rspuId;

    private String productName;

    private String categoryPath;

    /** 主图 URL（image_assets 主图，/api/v1/images/{imageId}）；无主图时为 null。 */
    private String primaryImageUrl;

    private String colorPrimaryName;

    /** 材质标签原文（materialTags JSON 字符串，MVP 直接透传）。 */
    private String material;

    /** 代表规格尺寸原文（取代表变体的 sizeText）。 */
    private String sizeText;

    private BigDecimal retailPrice;

    /** 融合排名分（P2 RRF 融合分；纯结构化检索时为结构化通道 RRF 分），落 agent_recommend_item.rank_score。 */
    private BigDecimal rankScore;

    /** 命中条件描述（用于 evidence）。 */
    private List<String> matchedConditions;
}
