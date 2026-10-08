package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.domain.EffectiveConstraintComposer;
import com.rsdp.agent.domain.FloorPlanRoomFact;
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
    private final EffectiveConstraintComposer constraintComposer;

    public ProductSearchNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                             MarketingProductQueryService queryService,
                             EffectiveConstraintComposer constraintComposer) {
        super(eventBus, runRecorder);
        this.queryService = queryService;
        this.constraintComposer = constraintComposer;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_PRODUCT_SEARCH;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        // W2：有效需求约束 + 当前房间空间事实 → 最终检索条件（空间硬约束只收紧不放宽）
        FloorPlanRoomFact activeRoom = ctx.getFloorPlanContext() != null
            ? ctx.getFloorPlanContext().activeRoom() : null;
        ProductSearchCriteria criteria = constraintComposer.compose(ctx.getConstraints(), activeRoom);
        ProductSearchResult result = queryService.search(criteria);
        ctx.setSearchCriteria(criteria);
        ctx.setSearchResult(result);
        log.info("Agent 检索完成，runId={}, totalMatched={}, items={}",
            ctx.getRunId(), result.getTotalMatched(), result.getItems() != null ? result.getItems().size() : 0);
        return Map.of();
    }

}
