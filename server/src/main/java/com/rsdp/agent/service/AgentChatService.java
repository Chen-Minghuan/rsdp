package com.rsdp.agent.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Consumer;

/**
 * Agent LLM 调用封装（DashScope ChatModel，模型名走 spring.ai.dashscope.chat.options.model 配置）。
 *
 * <p>提供两种调用：非流式 {@link #call}（JSON 抽取类任务）与流式
 * {@link #streamCollect}（面向用户的文案，token 经回调实时下发 SSE）。</p>
 */
@Slf4j
@Service
public class AgentChatService {

    /** 容错 JSON 解析器：忽略未知字段。 */
    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatModel chatModel;

    public AgentChatService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 非流式调用，返回完整文本。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return 模型输出文本（失败时抛异常，由调用方降级）
     */
    public String call(String systemPrompt, String userPrompt) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        ChatResponse response = chatModel.call(prompt);
        String text = response.getResult() != null && response.getResult().getOutput() != null
            ? response.getResult().getOutput().getText() : null;
        return text != null ? text : "";
    }

    /**
     * 流式调用：逐 token 回调，返回拼接后的完整文本（阻塞至流结束）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @param onToken      token 回调（SSE 下发）
     * @return 完整文本
     */
    public String streamCollect(String systemPrompt, String userPrompt, Consumer<String> onToken) {
        Prompt prompt = new Prompt(List.of(new SystemMessage(systemPrompt), new UserMessage(userPrompt)));
        Flux<ChatResponse> flux = chatModel.stream(prompt);
        StringBuilder full = new StringBuilder();
        flux.doOnNext(chunk -> {
                if (chunk.getResult() == null || chunk.getResult().getOutput() == null) {
                    return;
                }
                String delta = chunk.getResult().getOutput().getText();
                if (delta != null && !delta.isEmpty()) {
                    full.append(delta);
                    onToken.accept(delta);
                }
            })
            .blockLast();
        return full.toString();
    }

    /**
     * 容错解析模型输出的 JSON：去除 ```json 代码块包裹，截取第一个 { 到最后一个 }。
     *
     * @param raw  模型原始输出
     * @param type 目标类型
     * @return 解析结果；无法解析返回 null（调用方走降级路径）
     */
    public <T> T parseJson(String raw, Class<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            log.warn("LLM 输出不含 JSON 对象，按解析失败处理: {}", abbreviate(raw));
            return null;
        }
        try {
            return LENIENT_MAPPER.readValue(text.substring(start, end + 1), type);
        } catch (Exception e) {
            log.warn("LLM JSON 解析失败: {}", abbreviate(raw), e);
            return null;
        }
    }

    private String abbreviate(String raw) {
        return raw.length() > 200 ? raw.substring(0, 200) + "..." : raw;
    }
}
