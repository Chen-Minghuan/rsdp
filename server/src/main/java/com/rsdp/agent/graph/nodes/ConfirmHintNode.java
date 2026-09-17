package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 确认单品引导节点：用户表达「就要这款」意图时，引导其点击卡片上的确认按钮。
 *
 * <p>不调用 LLM：确认动作必须经用户显式点击按钮（HITL 确认点），
 * 避免模型替用户做确认决策。assistant 消息以 notice 类型落库。</p>
 */
@Component
public class ConfirmHintNode extends AbstractAgentNode {

    private static final String HINT = "好的，看中哪款直接点卡片上的「确认这款」按钮就能锁定主体产品；也可以继续告诉我调整方向。";

    public ConfirmHintNode(AgentEventBus eventBus, AgentRunRecorder runRecorder) {
        super(eventBus, runRecorder);
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_CONFIRM_HINT;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        ctx.appendAssistantText(HINT);
        ctx.setNotice(true);
        eventBus.emitToken(ctx.getRunId(), HINT);
        return Map.of();
    }
}
