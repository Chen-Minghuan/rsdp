package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 写操作引导节点（P2）：用户表达报价/生成方案/下单意图时，引导其使用页面上的显式按钮。
 *
 * <p>不调用 LLM、不执行任何写操作：报价/方案/下单必须经用户显式点击按钮（HITL 确认点），
 * 避免模型替用户触发写操作。assistant 消息以 notice 类型落库。</p>
 */
@Component
public class ActionHintNode extends AbstractAgentNode {

    private static final String QUOTE_HINT =
        "好的，已确认的产品可以在右侧「已确认清单」点「生成报价」，系统会按正式定价口径计算售价明细，报价确认后还能一键生成方案去下单。";

    private static final String SCHEME_HINT =
        "下单需要先生成方案：在右侧「已确认清单」生成报价后点「生成方案去下单」，跳转到方案详情页就能走正式下单流程。";

    private static final String DEFAULT_HINT =
        "已确认的产品可以在右侧清单生成报价，再生成方案去下单；也可以继续告诉我调整方向。";

    public ActionHintNode(AgentEventBus eventBus, AgentRunRecorder runRecorder) {
        super(eventBus, runRecorder);
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_ACTION_HINT;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        String intent = state.value(AgentStateKeys.STATE_INTENT, "");
        String hint = switch (intent) {
            case AgentStateKeys.INTENT_REQUEST_QUOTE -> QUOTE_HINT;
            case AgentStateKeys.INTENT_EXPORT_SCHEME -> SCHEME_HINT;
            default -> DEFAULT_HINT;
        };
        ctx.appendAssistantText(hint);
        ctx.setNotice(true);
        eventBus.emitToken(ctx.getRunId(), hint);
        return Map.of();
    }
}
