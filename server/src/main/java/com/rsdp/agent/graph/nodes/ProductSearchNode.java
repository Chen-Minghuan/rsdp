package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.domain.MarketingProductQueryService;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 产品检索节点：需求约束 → ProductSearchCriteria → 领域查询服务。
 *
 * <p>检索结果放入运行上下文（不进 OverAllState/checkpoint），供 RecommendNode 消费。</p>
 */
@Slf4j
@Component
public class ProductSearchNode extends AbstractAgentNode {

    private final MarketingProductQueryService queryService;

    public ProductSearchNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                             MarketingProductQueryService queryService) {
        super(eventBus, runRecorder);
        this.queryService = queryService;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_PRODUCT_SEARCH;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        ProductSearchCriteria criteria = toCriteria(ctx.getConstraints());
        ProductSearchResult result = queryService.search(criteria);
        ctx.setSearchCriteria(criteria);
        ctx.setSearchResult(result);
        log.info("Agent 检索完成，runId={}, totalMatched={}, items={}",
            ctx.getRunId(), result.getTotalMatched(), result.getItems() != null ? result.getItems().size() : 0);
        return Map.of();
    }

    /** 需求约束 → 检索条件（sofaForm/areaM2/note 等不下推，留给推荐理由参考）。 */
    private ProductSearchCriteria toCriteria(RequirementConstraints constraints) {
        ProductSearchCriteria criteria = new ProductSearchCriteria();
        if (constraints == null) {
            return criteria;
        }
        criteria.setCategoryCode(constraints.getCategoryCode());
        criteria.setStyle(constraints.getStyle());
        criteria.setMaterial(constraints.getMaterial());
        criteria.setColor(constraints.getColor());
        criteria.setBudgetMax(constraints.getBudgetMax());
        criteria.setMaxWidthMm(constraints.getMaxWidthMm());
        criteria.setMinWidthMm(constraints.getMinWidthMm());
        return criteria;
    }
}
