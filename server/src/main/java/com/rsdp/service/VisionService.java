package com.rsdp.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.config.SixDimSchemaConfig;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.OpenAiChatMessage;
import com.rsdp.dto.OpenAiChatRequest;
import com.rsdp.dto.OpenAiChatResponse;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.CategoryDict;
import com.rsdp.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

    @Value("${rsdp.ai.mock.enabled:false}")
    private boolean mockEnabled;

    private static final String SYSTEM_PROMPT = """
        你是家具产品分析专家。请对用户提供的产品图片进行分析，输出 JSON 格式。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String DEFAULT_STYLE_ENUM = "中古风、奶油风、侘寂风、意式、法式、包豪斯、工业风、新中式、孟菲斯";
    private static final String DEFAULT_SCENE_ENUM = "客厅、卧室、书房、办公室、酒店、咖啡厅";
    private static final String DEFAULT_MATERIAL_ENUM = "实木、皮革、亚麻、金属、玻璃、石材";

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

            if (mockEnabled) {
                log.info("AI 识别 Mock 已启用，返回模拟识别结果");
                return buildMockLabels();
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
     * 构造开发/测试环境使用的模拟 AI 识别结果。
     *
     * <p>当未配置真实 AI API 密钥或显式启用 Mock 时，返回稳定、合法的结构化数据，
     * 保证新品录入流程可继续执行，便于本地联调。</p>
     *
     * @return 模拟识别标签
     */
    private AiLabels buildMockLabels() {
        AiLabels labels = new AiLabels();
        labels.setStyle("MC");
        labels.setSixDimTags(Map.of(
            "A", "直线轮廓",
            "B", "高靠背",
            "C", "直扶手",
            "D", "金属腿",
            "E", "仿皮",
            "F", "海绵软包"
        ));
        labels.setColorPrimaryName("米白");
        labels.setColorPrimaryHsv(List.of(40.0, 0.15, 0.95));
        labels.setMaterialTags(List.of("PE"));
        labels.setSceneTags(List.of("LIVING"));
        labels.setConfidence("mid");

        OcrResult ocr = new OcrResult();
        ocr.setRawText("MOCK-PRODUCT 休闲椅 560*580*780mm");
        ocr.setProductName("Mock 休闲椅");
        ocr.setModelNumber("MOCK-001");
        ocr.setBrand("Mock Brand");
        ocr.setFactoryName("Mock Factory");
        ocr.setDimensionText("560*580*780mm");
        Dimensions dimensions = new Dimensions();
        dimensions.setW(560);
        dimensions.setD(580);
        dimensions.setH(780);
        dimensions.setUnit("mm");
        ocr.setDimensions(dimensions);
        ocr.setMaterialDescription("PE仿藤+金属框架");
        ocr.setColorText("米白色");
        ocr.setPriceText("¥1200");
        ocr.setPrice(new java.math.BigDecimal("1200"));
        ocr.setCurrency("CNY");
        Map<String, Object> otherInfo = new HashMap<>();
        otherInfo.put("warranty", "3年质保");
        otherInfo.put("moq", 10);
        otherInfo.put("leadTimeDays", 30);
        otherInfo.put("netWeightKg", 12.5);
        otherInfo.put("packageSize", "600*620*820mm");
        otherInfo.put("notes", "AI Mock 数据");
        ocr.setOtherInfo(otherInfo);
        labels.setOcr(ocr);
        return labels;
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
            String enumText = dictService.listByType(dictType).stream()
                .map(CategoryDict::getDictName)
                .filter(name -> name != null && !name.isBlank())
                .sorted()
                .collect(Collectors.joining("、"));
            if (!enumText.isBlank()) {
                return enumText;
            }
            log.warn("字典枚举为空，使用默认兜底枚举，dictType={}", dictType);
        } catch (Exception e) {
            log.warn("读取字典枚举失败，使用默认兜底枚举，dictType={}", dictType, e);
        }
        return switch (dictType) {
            case "style" -> DEFAULT_STYLE_ENUM;
            case "scene" -> DEFAULT_SCENE_ENUM;
            case "material" -> DEFAULT_MATERIAL_ENUM;
            default -> "";
        };
    }

    /**
     * PDF 页面产品区域检测提示词。
     * 要求 AI 对连续的多张 PDF 页面图片逐页分析，输出产品位置框和页面类型。
     */
    private static final String PAGE_DETECTION_SYSTEM_PROMPT = """
        你是家具产品目录分析专家。请对用户提供的一系列 PDF 页面图片逐页分析，
        判断每页类型并输出页面中每个产品的相对位置框（bbox）。
        只输出 JSON 数组，不要任何其他文字说明。
        """;

    private static final String PAGE_DETECTION_USER_PROMPT_TEMPLATE = """
        下面是 %d 张连续的 PDF 页面图片，请按顺序逐页分析。

        对每一页，判断其类型并输出产品中每个产品的位置信息：
        - pageType: product（产品页）/ cover（封面）/ toc（目录）/ separator（分隔页）/ blank（空白页）/ unknown（未知）
        - products: 当 pageType=product 时，列出该页中所有产品的位置框和预估品类码

        bbox 使用相对于页面宽高的比例坐标（0.0 ~ 1.0）：
        {"x": 左上角 x, "y": 左上角 y, "w": 宽度, "h": 高度}

        预估品类码必须从以下枚举中精确选择，无法判断时填 null：
        %s

        输出必须是一个 JSON 数组，数组长度严格等于 %d（图片数量），第 i 个元素对应第 i 张图片：
        [
          {
            "pageType": "product",
            "products": [
              {"bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5}, "estimatedCategory": "SF"}
            ]
          },
          ...
        ]

        关键约束：即使页面很多，也必须输出完整、合法的 JSON 数组，不能省略结尾括号或截断任何对象。
        只输出 JSON 数组，不要任何其他文字说明。
        """;

    /**
     * 对多张 PDF 页面图片进行产品区域检测。
     *
     * @param pageImages   页面图片流列表，顺序即为页码顺序
     * @param categoryHint 品类提示，为空时使用所有品类枚举
     * @return 每页的产品区域列表，顺序与输入一致
     */
    public List<DocumentProductRegion> detectPageRegions(List<InputStream> pageImages, String categoryHint) {
        if (pageImages == null || pageImages.isEmpty()) {
            return List.of();
        }
        try {
            List<String> base64Images = new java.util.ArrayList<>();
            for (InputStream stream : pageImages) {
                byte[] bytes = stream.readAllBytes();
                if (bytes.length == 0) {
                    throw new ExternalServiceException("页面图片流为空");
                }
                base64Images.add(Base64.getEncoder().encodeToString(bytes));
            }

            String categoryEnum = buildCategoryEnumText();
            String userPrompt = PAGE_DETECTION_USER_PROMPT_TEMPLATE.formatted(
                base64Images.size(), categoryEnum, base64Images.size());

            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", PAGE_DETECTION_SYSTEM_PROMPT),
                    OpenAiChatMessage.multiVision("user", userPrompt, base64Images)
                ))
                .temperature(0.2)
                .build();

            String json = executeChat(request, "PDF 页面区域检测");
            return parsePageRegions(json, pageImages.size());
        } catch (IOException e) {
            log.error("读取页面图片流失败", e);
            throw new ExternalServiceException("读取页面图片流失败", e);
        }
    }

    private String buildCategoryEnumText() {
        try {
            return dictService.listByType("category").stream()
                .filter(d -> d.getDictCode() != null && !d.getDictCode().isBlank())
                .map(d -> d.getDictCode() + "(" + (d.getDictName() != null ? d.getDictName() : "") + ")")
                .sorted()
                .collect(Collectors.joining("、"));
        } catch (Exception e) {
            log.warn("读取品类字典失败", e);
            return "";
        }
    }

    private List<DocumentProductRegion> parsePageRegions(String json, int expectedSize) {
        if (json == null || json.isBlank()) {
            throw new ExternalServiceException("AI 页面检测返回为空");
        }
        try {
            List<?> rawList = objectMapper.readValue(json, List.class);
            if (rawList == null || rawList.size() != expectedSize) {
                throw new ExternalServiceException("AI 页面检测返回数组长度不匹配，期望 " + expectedSize + "，实际 " +
                    (rawList == null ? 0 : rawList.size()));
            }

            List<DocumentProductRegion> regions = new java.util.ArrayList<>();
            for (int i = 0; i < rawList.size(); i++) {
                DocumentProductRegion region = parseSingleRegion(rawList.get(i));
                region.setPageIndex(i);
                regions.add(region);
            }
            return regions;
        } catch (ExternalServiceException e) {
            throw e;
        } catch (Exception e) {
            log.warn("AI 页面检测结果 JSON 可能截断，尝试流式解析已完整对象，expectedSize={}", expectedSize);
            List<DocumentProductRegion> recovered = parsePageRegionsStreaming(json, expectedSize);
            if (recovered != null && !recovered.isEmpty()) {
                log.info("流式解析恢复 {} 页结果", recovered.size());
                return recovered;
            }
            log.error("解析 AI 页面检测结果失败，json={}", json, e);
            throw new ExternalServiceException("解析 AI 页面检测结果失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<DocumentProductRegion> parsePageRegionsStreaming(String json, int expectedSize) {
        List<DocumentProductRegion> regions = new java.util.ArrayList<>();
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                return null;
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                Map<String, Object> map = parser.readValueAs(Map.class);
                DocumentProductRegion region = parseSingleRegion(map);
                region.setPageIndex(regions.size());
                regions.add(region);
            }
        } catch (Exception e) {
            log.warn("流式解析 AI 页面检测结果中断，已恢复 {} 页", regions.size());
        }
        if (regions.size() < expectedSize) {
            for (int i = regions.size(); i < expectedSize; i++) {
                DocumentProductRegion fallback = new DocumentProductRegion();
                fallback.setPageIndex(i);
                fallback.setPageType("unknown");
                regions.add(fallback);
            }
        }
        return regions;
    }

    @SuppressWarnings("unchecked")
    private DocumentProductRegion parseSingleRegion(Object raw) {
        DocumentProductRegion region = new DocumentProductRegion();
        if (!(raw instanceof Map<?, ?> map)) {
            region.setPageType("unknown");
            return region;
        }

        Object pageType = map.get("pageType");
        region.setPageType(pageType != null ? pageType.toString() : "unknown");

        Object products = map.get("products");
        if (products instanceof List<?> productList) {
            List<DocumentProductRegion.PageProduct> pageProducts = new java.util.ArrayList<>();
            for (Object p : productList) {
                if (p instanceof Map<?, ?> pm) {
                    DocumentProductRegion.PageProduct pp = new DocumentProductRegion.PageProduct();
                    pp.setBbox(parseBoundingBox(pm.get("bbox")));
                    Object category = pm.get("estimatedCategory");
                    pp.setEstimatedCategory(category != null ? category.toString() : null);
                    pageProducts.add(pp);
                }
            }
            region.setProducts(pageProducts);
        }
        return region;
    }

    @SuppressWarnings("unchecked")
    private ProductBoundingBox parseBoundingBox(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            double x = parseDoubleValue(map.get("x"));
            double y = parseDoubleValue(map.get("y"));
            double w = parseDoubleValue(map.get("w"));
            double h = parseDoubleValue(map.get("h"));
            ProductBoundingBox bbox = new ProductBoundingBox(x, y, w, h);
            return bbox.isValid() ? bbox : null;
        } catch (Exception e) {
            log.warn("解析 bbox 失败", e);
            return null;
        }
    }

    private double parseDoubleValue(Object value) {
        if (value == null) {
            return 0.0;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString());
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
