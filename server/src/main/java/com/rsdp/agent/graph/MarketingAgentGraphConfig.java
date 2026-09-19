package com.rsdp.agent.graph;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategy;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.action.AsyncEdgeAction;
import com.alibaba.cloud.ai.graph.action.AsyncNodeAction;
import com.alibaba.cloud.ai.graph.checkpoint.config.SaverConfig;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.rsdp.agent.graph.nodes.ChitchatNode;
import com.rsdp.agent.graph.nodes.CompanionMatchNode;
import com.rsdp.agent.graph.nodes.ActionHintNode;
import com.rsdp.agent.graph.nodes.ConfirmHintNode;
import com.rsdp.agent.graph.nodes.FollowupNode;
import com.rsdp.agent.graph.nodes.ProductSearchNode;
import com.rsdp.agent.graph.nodes.RecommendNode;
import com.rsdp.agent.graph.nodes.RequirementPatchNode;
import com.rsdp.agent.service.AgentCheckpointStore;
import com.rsdp.agent.service.AgentRunContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 营销 Agent 图装配（SAA StateGraph）。
 *
 * <p>拓扑：START → requirement_patch →（条件边）→ followup / product_search → recommend /
 * chitchat / confirm_hint / companion_match / action_hint → END。每轮用户消息跑一次完整图
 * （run 边界即 HITL 边界），不做图内中断恢复；checkpoint 以 threadId=runId 经
 * {@link AgentCheckpointStore} 留痕。</p>
 *
 * <p>OverAllState 只放 ID 与小字符串（sessionId/runId/userMessage/intent），
 * 候选产品等运行期对象经 {@link AgentRunContext} 传递。</p>
 */
@Configuration
public class MarketingAgentGraphConfig {

    @Bean
    public CompiledGraph marketingAgentGraph(RequirementPatchNode requirementPatchNode,
                                             FollowupNode followupNode,
                                             ProductSearchNode productSearchNode,
                                             RecommendNode recommendNode,
                                             ChitchatNode chitchatNode,
                                             ConfirmHintNode confirmHintNode,
                                             CompanionMatchNode companionMatchNode,
                                             ActionHintNode actionHintNode,
                                             AgentRouter router,
                                             AgentCheckpointStore checkpointStore) throws GraphStateException {
        StateGraph graph = new StateGraph("marketing-agent", this::keyStrategies)
            .addNode(AgentStateKeys.NODE_REQUIREMENT_PATCH, AsyncNodeAction.node_async(requirementPatchNode))
            .addNode(AgentStateKeys.NODE_FOLLOWUP, AsyncNodeAction.node_async(followupNode))
            .addNode(AgentStateKeys.NODE_PRODUCT_SEARCH, AsyncNodeAction.node_async(productSearchNode))
            .addNode(AgentStateKeys.NODE_RECOMMEND, AsyncNodeAction.node_async(recommendNode))
            .addNode(AgentStateKeys.NODE_CHITCHAT, AsyncNodeAction.node_async(chitchatNode))
            .addNode(AgentStateKeys.NODE_CONFIRM_HINT, AsyncNodeAction.node_async(confirmHintNode))
            .addNode(AgentStateKeys.NODE_COMPANION_MATCH, AsyncNodeAction.node_async(companionMatchNode))
            .addNode(AgentStateKeys.NODE_ACTION_HINT, AsyncNodeAction.node_async(actionHintNode))
            .addEdge(StateGraph.START, AgentStateKeys.NODE_REQUIREMENT_PATCH)
            .addConditionalEdges(AgentStateKeys.NODE_REQUIREMENT_PATCH,
                AsyncEdgeAction.edge_async(state -> {
                    String runId = state.value(AgentStateKeys.STATE_RUN_ID, "");
                    String intent = state.value(AgentStateKeys.STATE_INTENT, AgentStateKeys.INTENT_CHITCHAT);
                    return router.route(intent, AgentRunContext.require(runId));
                }),
                Map.of(
                    AgentStateKeys.ROUTE_FOLLOWUP, AgentStateKeys.NODE_FOLLOWUP,
                    AgentStateKeys.ROUTE_SEARCH, AgentStateKeys.NODE_PRODUCT_SEARCH,
                    AgentStateKeys.ROUTE_CHITCHAT, AgentStateKeys.NODE_CHITCHAT,
                    AgentStateKeys.ROUTE_CONFIRM_HINT, AgentStateKeys.NODE_CONFIRM_HINT,
                    AgentStateKeys.ROUTE_COMPANION, AgentStateKeys.NODE_COMPANION_MATCH,
                    AgentStateKeys.ROUTE_ACTION_HINT, AgentStateKeys.NODE_ACTION_HINT))
            .addEdge(AgentStateKeys.NODE_FOLLOWUP, StateGraph.END)
            .addEdge(AgentStateKeys.NODE_PRODUCT_SEARCH, AgentStateKeys.NODE_RECOMMEND)
            .addEdge(AgentStateKeys.NODE_RECOMMEND, StateGraph.END)
            .addEdge(AgentStateKeys.NODE_CHITCHAT, StateGraph.END)
            .addEdge(AgentStateKeys.NODE_CONFIRM_HINT, StateGraph.END)
            .addEdge(AgentStateKeys.NODE_COMPANION_MATCH, StateGraph.END)
            .addEdge(AgentStateKeys.NODE_ACTION_HINT, StateGraph.END);

        CompileConfig compileConfig = CompileConfig.builder()
            .saverConfig(SaverConfig.builder().register(checkpointStore.saver()).build())
            .build();
        return graph.compile(compileConfig);
    }

    /** 全部状态键使用替换策略（状态只放 ID 与小字符串，无追加语义）。 */
    private Map<String, KeyStrategy> keyStrategies() {
        return Map.of(
            AgentStateKeys.STATE_SESSION_ID, KeyStrategy.REPLACE,
            AgentStateKeys.STATE_RUN_ID, KeyStrategy.REPLACE,
            AgentStateKeys.STATE_USER_MESSAGE, KeyStrategy.REPLACE,
            AgentStateKeys.STATE_INTENT, KeyStrategy.REPLACE);
    }
}
