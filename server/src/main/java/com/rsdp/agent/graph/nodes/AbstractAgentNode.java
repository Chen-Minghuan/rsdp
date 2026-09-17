package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;

import java.util.Map;

/**
 * Agent 图节点基类：统一处理 runId 提取、SSE node 事件与 agent_run.current_node 留痕。
 */
public abstract class AbstractAgentNode implements NodeAction {

    protected final AgentEventBus eventBus;
    protected final AgentRunRecorder runRecorder;

    protected AbstractAgentNode(AgentEventBus eventBus, AgentRunRecorder runRecorder) {
        this.eventBus = eventBus;
        this.runRecorder = runRecorder;
    }

    /** 节点名（{@link AgentStateKeys} NODE_*）。 */
    protected abstract String nodeName();

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        String runId = state.value(AgentStateKeys.STATE_RUN_ID, "");
        AgentRunContext ctx = AgentRunContext.require(runId);
        eventBus.emitNode(runId, nodeName(), AgentStateKeys.labelOf(nodeName()));
        runRecorder.updateCurrentNode(runId, nodeName());
        return doApply(state, ctx);
    }

    /** 节点主逻辑。 */
    protected abstract Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) throws Exception;

    /** 运行 ID。 */
    protected String runId(OverAllState state) {
        return state.value(AgentStateKeys.STATE_RUN_ID, "");
    }
}
