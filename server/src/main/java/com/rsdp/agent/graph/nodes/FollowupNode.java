package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.graph.ConstraintCompleteness;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 追问节点：关键约束缺失且追问轮次未耗尽时，LLM 生成一句自然的追问（流式输出）。
 *
 * <p>追问后 run 结束（done），等用户回答即下一次 run —— run 边界即 HITL 边界。</p>
 */
@Slf4j
@Component
public class FollowupNode extends AbstractAgentNode {

    private static final String SYSTEM_PROMPT = """
        你是家居选品顾问。用户的关键选品约束还不完整，请用一句自然、简短的中文追问缺失信息。
        规则：
        - 一次只追问一个最关键的缺失项，语气友好专业
        - 只输出追问文本本身，不要解释、不要列表、不要 JSON
        - 可结合用户已提供的信息让追问更贴合（如已知风格可顺带确认）
        """;

    /** LLM 故障时的兜底追问。 */
    private static final String FALLBACK = "为了帮您更准确地挑选，能再告诉我一些信息吗？比如品类、预算或者尺寸方面的要求。";

    private final AgentChatService chatService;
    private final ConstraintCompleteness completeness;

    public FollowupNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                        AgentChatService chatService, ConstraintCompleteness completeness) {
        super(eventBus, runRecorder);
        this.chatService = chatService;
        this.completeness = completeness;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_FOLLOWUP;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        String text;
        try {
            text = chatService.streamCollect(SYSTEM_PROMPT, buildUserPrompt(ctx),
                token -> eventBus.emitToken(ctx.getRunId(), token));
        } catch (Exception e) {
            log.error("追问生成失败，使用兜底文案，runId={}", ctx.getRunId(), e);
            text = FALLBACK;
            eventBus.emitToken(ctx.getRunId(), text);
        }
        if (text == null || text.isBlank()) {
            text = FALLBACK;
            eventBus.emitToken(ctx.getRunId(), text);
        }
        ctx.appendAssistantText(text.trim());
        // 追问轮次统计依赖 assistant 消息 metadata 的 followup 标记
        ctx.setFollowup(true);
        return Map.of();
    }

    private String buildUserPrompt(AgentRunContext ctx) {
        List<String> missing = completeness.missingAspects(ctx.getConstraints());
        return "已收集的需求约束："
            + (ctx.getConstraints() != null ? ctx.getConstraints().toJson() : "{}")
            + "\n缺失的关键信息：" + String.join("、", missing)
            + "\n用户最新消息：" + ctx.getUserMessage();
    }
}
