package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 闲聊节点：与选品无关的消息，LLM 简短回复并自然引导回选品（流式输出）。
 */
@Slf4j
@Component
public class ChitchatNode extends AbstractAgentNode {

    private static final String SYSTEM_PROMPT = """
        你是家居选品顾问。用户说了一句与选品无直接关系的话，请用一两句简短友好的中文回应，
        并自然地把话题引回选品（例如询问对方想看什么产品）。只输出回复文本本身，不要 JSON、不要解释。
        """;

    private static final String FALLBACK = "我在呢。想看点什么产品？比如沙发、茶几，告诉我您的需求就行。";

    private final AgentChatService chatService;

    public ChitchatNode(AgentEventBus eventBus, AgentRunRecorder runRecorder, AgentChatService chatService) {
        super(eventBus, runRecorder);
        this.chatService = chatService;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_CHITCHAT;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        String text;
        try {
            text = chatService.streamCollect(SYSTEM_PROMPT, "用户说：" + ctx.getUserMessage(),
                token -> eventBus.emitToken(ctx.getRunId(), token));
        } catch (Exception e) {
            log.error("闲聊回复生成失败，使用兜底文案，runId={}", ctx.getRunId(), e);
            text = null;
        }
        if (text == null || text.isBlank()) {
            text = FALLBACK;
            eventBus.emitToken(ctx.getRunId(), text);
        }
        ctx.appendAssistantText(text.trim());
        return Map.of();
    }
}
