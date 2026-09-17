package com.rsdp.agent;

import com.rsdp.agent.dto.LlmRequirementExtraction;
import com.rsdp.agent.service.AgentChatService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link AgentChatService#parseJson} 单元测试（LLM JSON 容错解析：
 * RequirementPatchNode 经此方法解析需求抽取输出）。
 */
class AgentChatServiceParseJsonTest {

    private final AgentChatService chatService = new AgentChatService(mock(ChatModel.class));

    @Test
    void plainJsonShouldParse() {
        LlmRequirementExtraction result = chatService.parseJson(
            "{\"intent\":\"CHITCHAT\",\"operations\":[]}", LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("CHITCHAT");
        assertThat(result.getOperations()).isEmpty();
    }

    @Test
    void jsonWrappedInMarkdownFenceShouldParse() {
        String raw = "```json\n{\"intent\":\"NEW_REQUIREMENT\",\"operations\":[]}\n```";

        LlmRequirementExtraction result = chatService.parseJson(raw, LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("NEW_REQUIREMENT");
    }

    @Test
    void jsonWrappedInPlainFenceShouldParse() {
        String raw = "```\n{\"intent\":\"REFINE\"}\n```";

        LlmRequirementExtraction result = chatService.parseJson(raw, LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("REFINE");
    }

    @Test
    void jsonSurroundedByProseShouldParse() {
        String raw = "好的，以下是抽取结果：\n"
            + "{\"intent\":\"NEW_REQUIREMENT\",\"operations\":["
            + "{\"field\":\"categoryCode\",\"operation\":\"set\",\"value\":\"沙发\",\"evidence\":\"我想买沙发\"}]}\n"
            + "希望对你有帮助。";

        LlmRequirementExtraction result = chatService.parseJson(raw, LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("NEW_REQUIREMENT");
        assertThat(result.getOperations()).hasSize(1);
        assertThat(result.getOperations().get(0).getField()).isEqualTo("categoryCode");
        assertThat(result.getOperations().get(0).getValue()).isEqualTo("沙发");
    }

    @Test
    void invalidJsonShouldReturnNull() {
        assertThat(chatService.parseJson("{intent: 这是坏掉的}", LlmRequirementExtraction.class))
            .isNull();
    }

    @Test
    void textWithoutJsonObjectShouldReturnNull() {
        assertThat(chatService.parseJson("完全没有 JSON 的闲聊回复", LlmRequirementExtraction.class))
            .isNull();
    }

    @Test
    void blankOrNullShouldReturnNull() {
        assertThat(chatService.parseJson(null, LlmRequirementExtraction.class)).isNull();
        assertThat(chatService.parseJson("   ", LlmRequirementExtraction.class)).isNull();
    }

    @Test
    void unknownFieldsShouldBeIgnored() {
        LlmRequirementExtraction result = chatService.parseJson(
            "{\"intent\":\"REFINE\",\"unknownField\":123,\"operations\":null}",
            LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("REFINE");
        assertThat(result.getOperations()).isNull();
    }

    @Test
    void fenceWithTrailingProseShouldParse() {
        // 代码块后还有解释文字：截取第一个 { 到最后一个 }
        String raw = "```json\n{\"intent\":\"CHITCHAT\"}\n```\n以上是识别结果";

        LlmRequirementExtraction result = chatService.parseJson(raw, LlmRequirementExtraction.class);

        assertThat(result).isNotNull();
        assertThat(result.getIntent()).isEqualTo("CHITCHAT");
    }
}
