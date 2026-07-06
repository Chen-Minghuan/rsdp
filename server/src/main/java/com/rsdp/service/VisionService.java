package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.SixDimSchemaConfig;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.OpenAiChatMessage;
import com.rsdp.dto.OpenAiChatRequest;
import com.rsdp.dto.OpenAiChatResponse;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;
    private final DictService dictService;

    @Value("${rsdp.ai.model}")
    private String model;

    private static final String SYSTEM_PROMPT = """
        你是家具产品分析专家。请对用户提供的产品图片进行分析，输出 JSON 格式。
        只输出 JSON，不要任何其他文字说明。
        """;

    /**
     * 用户提示词模板。风格、场景、材质枚举会在运行时从 category_dict 动态注入，
     * 六维标签维度定义会根据产品类别动态选择。
     */
    private static final String USER_PROMPT_TEMPLATE = """
        请分析这张家具产品图，输出以下 JSON 字段：
        {
          "style": "风格名称，必须从以下枚举中精确选择：%s。严禁使用枚举外的风格名称。",
          "sixDimTags": {
            "A": "维度A",
            "B": "维度B",
            "C": "维度C",
            "D": "维度D",
            "E": "维度E",
            "F": "维度F"
          },
          "colorPrimaryName": "主色名称，如：焦糖棕、米白、原木色",
          "colorPrimaryHsv": [H值0-360, S值0-1, V值0-1],
          "materialTags": ["材质1", "材质2"],
          "sceneTags": ["适用场景1", "适用场景2"],
          "confidence": "high|mid|low",
          "ocr": {
            "rawText": "图片中所有可见文字，按原文完整输出，不要遗漏",
            "productName": "产品名称",
            "modelNumber": "型号/款号。必须是字母/数字组合或包含型号意义的编码，如 A2038、FS-MC-001。如果只是 #、*、- 等符号或无法判断，填 null",
            "brand": "品牌名",
            "factoryName": "工厂/厂家名",
            "dimensionText": "原始尺寸文字，保留所有规格，如 2380*840*910/2600*840*910",
            "dimensions": { "w": 数值或null, "d": 数值或null, "h": 数值或null, "unit": "mm|cm|m|inch" },
            "materialDescription": "材质说明原文。只提取具体材质成分，如'橡木框架+亚麻布软包'；遇到品牌口号、标语（如'用真实木 造好家具'）应填 null",
            "colorText": "颜色文字",
            "priceText": "价格文字",
            "price": 数值或null,
            "currency": "CNY",
            "otherInfo": {
              "warranty": "质保信息",
              "moq": 数值或null,
              "leadTimeDays": 数值或null,
              "netWeightKg": 数值或null,
              "packageSize": "包装尺寸文字",
              "notes": "其他文字信息"
            }
          }
        }
        %s
        风格、场景、材质的枚举约束如下，请优先从中选择：
        - 风格（style）：%s
        - 场景（scene）：%s
        - 材质（material）：%s
        如果无法判断某个字段或图片中没有对应文字，填 null 或 "unknown"。
        只输出 JSON，不要任何其他文字说明。
        """;

    /**
     * 识别图片，使用默认（通用）六维标签定义。
     */
    public AiLabels recognizeImage(InputStream imageStream) {
        return recognizeImage(imageStream, null);
    }

    /**
     * 识别图片，按产品类别使用对应的六维标签定义。
     *
     * @param imageStream  图片流
     * @param categoryCode 产品品类码，如 FS/TB/FC；为空时使用通用定义
     * @return AI 识别标签
     */
    public AiLabels recognizeImage(InputStream imageStream, String categoryCode) {
        try {
            byte[] imageBytes = imageStream.readAllBytes();
            if (imageBytes.length == 0) {
                throw new ExternalServiceException("图片流为空");
            }
            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            String userPrompt = buildUserPrompt(categoryCode);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", userPrompt, base64)
                ))
                .temperature(0.3)
                .build();

            String json = executeChat(request, "AI 识别");
            return objectMapper.readValue(json, AiLabels.class);

        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new ExternalServiceException("读取图片流失败", e);
        }
    }

    /**
     * 构建用户提示词，运行时从 category_dict 注入风格、场景、材质枚举，
     * 并按品类码注入对应的六维标签维度定义。
     *
     * @param categoryCode 产品品类码
     * @return 完整的用户提示词
     */
    private String buildUserPrompt(String categoryCode) {
        String styleEnum = buildEnumText("style");
        String sceneEnum = buildEnumText("scene");
        String materialEnum = buildEnumText("material");
        String sixDimDescription = SixDimSchemaConfig.buildPromptDescription(categoryCode);
        return USER_PROMPT_TEMPLATE.formatted(styleEnum, sixDimDescription, styleEnum, sceneEnum, materialEnum);
    }

    /**
     * 从字典服务读取指定类型的有效名称，拼接为顿号分隔的枚举文本。
     *
     * @param dictType 字典类型
     * @return 枚举文本，如"中古风、奶油风、侘寂风"
     */
    private String buildEnumText(String dictType) {
        try {
            return dictService.listByType(dictType).stream()
                .map(CategoryDict::getDictName)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .collect(Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("读取字典枚举失败，dictType={}", dictType, e);
            return "";
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
        OpenAiChatResponse response;
        try {
            response = aiRestClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(OpenAiChatResponse.class);
        } catch (Exception e) {
            throw new ExternalServiceException("AI API 调用失败: " + e.getMessage(), e);
        }
        long cost = System.currentTimeMillis() - start;

        log.info("{}完成，耗时 {}ms", taskName, cost);

        if (response == null || response.getChoices() == null || response.getChoices().isEmpty()) {
            throw new ExternalServiceException("AI API 返回为空");
        }

        OpenAiChatResponse.Choice choice = response.getChoices().get(0);
        if (choice.getMessage() == null) {
            throw new ExternalServiceException("AI API 返回消息为空");
        }

        String content = choice.getMessage().getContent();
        if (content == null || content.isBlank()) {
            throw new ExternalServiceException("AI API 返回内容为空");
        }

        // 清理可能的 markdown 代码块标记
        return content
            .replaceAll("```json\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();
    }
}
