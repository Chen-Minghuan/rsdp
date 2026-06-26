package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.OpenAiChatMessage;
import com.rsdp.dto.OpenAiChatRequest;
import com.rsdp.dto.OpenAiChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;

    @Value("${rsdp.ai.model}")
    private String model;

    private static final String SYSTEM_PROMPT = """
        你是家具产品分析专家。请对用户提供的产品图片进行分析，输出 JSON 格式。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String USER_PROMPT = """
        请分析这张家具产品图，输出以下 JSON 字段：
        {
          "style": "风格名称，如：中古风、奶油风、意式极简、工业风等",
          "sixDimTags": {
            "A": "轮廓形态",
            "B": "靠背/背部特征",
            "C": "扶手特征",
            "D": "腿部/底座特征",
            "E": "表面材质",
            "F": "软包填充形态"
          },
          "colorPrimaryName": "主色名称，如：焦糖棕、米白、原木色",
          "colorPrimaryHsv": [H值0-360, S值0-1, V值0-1],
          "materialTags": ["材质1", "材质2"],
          "sceneTags": ["适用场景1", "适用场景2"],
          "confidence": "high|mid|low"
        }
        如果无法判断某个字段，填"unknown"。
        """;

    public AiLabels recognizeImage(InputStream imageStream) {
        try {
            byte[] imageBytes = imageStream.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", USER_PROMPT, base64)
                ))
                .temperature(0.3)
                .build();

            String json = executeChat(request, "AI 识别");
            return objectMapper.readValue(json, AiLabels.class);

        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new RuntimeException("读取图片流失败", e);
        }
    }

    /**
     * 纯文本对话，用于非图片类 AI 任务（如搭配推荐）。
     *
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户提示词
     * @return AI 返回的文本内容
     */
    public String chatText(String systemPrompt, String userPrompt) {
        OpenAiChatRequest request = OpenAiChatRequest.builder()
            .model(model)
            .messages(List.of(
                OpenAiChatMessage.text("system", systemPrompt),
                OpenAiChatMessage.text("user", userPrompt)
            ))
            .temperature(0.5)
            .build();

        return executeChat(request, "AI 文本对话");
    }

    private String executeChat(OpenAiChatRequest request, String taskName) {
        long start = System.currentTimeMillis();
        OpenAiChatResponse response = aiRestClient.post()
            .uri("/chat/completions")
            .body(request)
            .retrieve()
            .body(OpenAiChatResponse.class);
        long cost = System.currentTimeMillis() - start;

        log.info("{}完成，耗时 {}ms", taskName, cost);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new RuntimeException("API 返回为空");
        }

        String content = response.getChoices().get(0).getMessage().getContent();

        // 清理可能的 markdown 代码块标记
        return content
            .replaceAll("```json\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();
    }
}
