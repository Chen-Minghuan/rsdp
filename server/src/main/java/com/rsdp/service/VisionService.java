package com.rsdp.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.CategoryShadowPrediction;
import com.rsdp.dto.Dimensions;
import com.rsdp.dto.DocumentProductRegion;
import com.rsdp.dto.FloorPlanDetectResult;
import com.rsdp.dto.OcrResult;
import com.rsdp.dto.OpenAiChatMessage;
import com.rsdp.dto.OpenAiChatRequest;
import com.rsdp.dto.OpenAiChatResponse;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.KnowledgeProductType;
import com.rsdp.exception.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.util.StringUtils;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisionService {

    private final RestClient aiRestClient;
    private final ObjectMapper objectMapper;
    private final DictService dictService;
    private final SixDimSchemaService sixDimSchemaService;

    @Value("${rsdp.ai.model}")
    private String model;

    @Value("${rsdp.ai.mock.enabled:false}")
    private boolean mockEnabled;

    /**
     * 户型图识别像素预算下限（DashScope qwen-vl content-part 参数 min_pixels）。
     * 默认 256×28×28=200704：保证小尺寸裁剪图不被服务端过度降采样，细墙线/尺寸小字可辨。
     */
    @Value("${rsdp.ai.floor-plan-min-pixels:200704}")
    private Integer floorPlanMinPixels;

    /**
     * 户型图识别像素预算上限（DashScope qwen-vl content-part 参数 max_pixels）。
     * 默认 4194304（4M px）：高于 qwen3-vl-plus 默认上限 2621440，避免 2000px 放大后的
     * 裁剪图（约 2.7M px）被服务端二次压缩丢失墙线细节；端点允许上限 16777216。
     */
    @Value("${rsdp.ai.floor-plan-max-pixels:4194304}")
    private Integer floorPlanMaxPixels;

    /**
     * 逐房间二次精修房间数上限（rsdp.floor-plan.refine-max-rooms，默认 12）。
     * 初检房间数超限时按 bbox 面积从大到小取前 N 个精修，避免精修调用过多触发限流。
     */
    @Value("${rsdp.floor-plan.refine-max-rooms:12}")
    private Integer refineMaxRooms;

    private static final String SYSTEM_PROMPT = """
        你是家具产品分析专家。请对用户提供的产品图片进行分析，输出 JSON 格式。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String DEFAULT_STYLE_ENUM = "中古风、奶油风、侘寂风、意式、法式、包豪斯、工业风、新中式、孟菲斯";
    private static final String DEFAULT_SCENE_ENUM = "客厅、卧室、书房、办公室、酒店、咖啡厅";
    private static final String DEFAULT_MATERIAL_ENUM = "实木、皮革、亚麻、金属、玻璃、石材";
    private static final String DEFAULT_FABRIC_ENUM = "亚麻/棉麻、科技布、天鹅绒/绒布、真皮、超纤皮、PU/PVC革";

    /**
     * 用户提示词模板。风格、场景、材质枚举会在运行时从 category_dict 动态注入，
     * 六维标签维度定义会根据产品类别动态选择。
     */
    private static final String USER_PROMPT_TEMPLATE = """
        请分析这张家具产品图，输出以下 JSON 字段：
        {
          "style": "风格名称，必须从以下枚举中精确选择：%s。严禁使用枚举外的风格名称。",
          "secondaryStyles": ["若产品明显也适用于其他风格，从同一枚举中再选最多2个作为备选风格（不含主风格）；没有明显备选则输出空数组"],
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
          "fabricTags": ["面料1", "面料2"],
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
        风格、场景、材质、面料的枚举约束如下，请优先从中选择：
        - 风格（style）：%s
        - 场景（scene）：%s
        - 材质（material）：%s
        - 面料（fabric）：%s
        面料（fabricTags）指沙发、床垫、软包椅等软体商品的接触面面料（如亚麻、科技布、真皮），
        与框架等结构材质（materialTags）区分；非软体商品或无法判断时填 []。
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
        try (imageStream) {
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
                .maxTokens(4096)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            String json = executeChat(request, "AI 识别");
            return parseLabels(json);

        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new ExternalServiceException("读取图片流失败", e);
        }
    }

    /**
     * 解析 AI 返回的 JSON 为识别标签。
     *
     * @param json AI 原始返回
     * @return 识别标签
     */
    private AiLabels parseLabels(String json) {
        try {
            return objectMapper.readValue(normalizeSixDimTagsJson(json), AiLabels.class);
        } catch (IOException e) {
            log.error("解析 AI 识别结果失败，json={}", json, e);
            throw new ExternalServiceException("解析 AI 识别结果失败", e);
        }
    }

    /**
     * 六维标签容错归一：AI 偶发把某维返回为数组（如 "E": ["实木","布艺"]），
     * 而 AiLabels.sixDimTags 是 Map&lt;String,String&gt;，直接反序列化会抛
     * MismatchedInputException 导致整个识别结果判失败。此处把数组值合并为
     * "/" 分隔的字符串（空数组归一为 null），其余内容原样保留。
     *
     * @param json AI 原始返回
     * @return 归一后的 JSON；解析失败时原样返回（由后续反序列化报错）
     */
    static String normalizeSixDimTagsJson(String json) {
        try {
            com.fasterxml.jackson.databind.JsonNode root = new ObjectMapper().readTree(json);
            com.fasterxml.jackson.databind.JsonNode tags = root.path("sixDimTags");
            if (!tags.isObject()) {
                return json;
            }
            com.fasterxml.jackson.databind.node.ObjectNode tagObj =
                (com.fasterxml.jackson.databind.node.ObjectNode) tags;
            List<String> fieldNames = new ArrayList<>();
            tagObj.fieldNames().forEachRemaining(fieldNames::add);
            boolean changed = false;
            for (String field : fieldNames) {
                com.fasterxml.jackson.databind.JsonNode value = tagObj.get(field);
                if (value == null || !value.isArray()) {
                    continue;
                }
                List<String> parts = new ArrayList<>();
                value.forEach(node -> {
                    if (node.isTextual() && StringUtils.hasText(node.asText())) {
                        parts.add(node.asText().trim());
                    }
                });
                if (parts.isEmpty()) {
                    tagObj.putNull(field);
                } else {
                    tagObj.put(field, String.join("/", parts));
                }
                changed = true;
            }
            return changed ? root.toString() : json;
        } catch (Exception e) {
            log.warn("六维标签 JSON 归一失败，按原文继续：{}", e.getMessage());
            return json;
        }
    }

    /**
     * 识别图片（双图模式）：裁剪图负责形态识别，原图负责 OCR 文字提取。
     *
     * <p>主图智能裁剪会把原图中的文字版面（品名/型号/尺寸/价格等）裁掉，
     * 单用裁剪图识别会导致 OCR 字段全空、产品名回退为品类名。
     * 双图模式将两张图一并发送，由 prompt 分工：形态看裁剪图、文字看原图。</p>
     *
     * @param croppedImageStream 裁剪后的主体图输入流（方法内关闭）
     * @param originalImageBytes 原始上传图字节（OCR 文字提取依据）；为空时退化为单图识别
     * @param categoryCode       产品品类码，如 FS/TB/FC；为空时使用通用定义
     * @return AI 识别标签
     */
    public AiLabels recognizeImage(InputStream croppedImageStream, byte[] originalImageBytes, String categoryCode) {
        try (croppedImageStream) {
            byte[] croppedBytes = croppedImageStream.readAllBytes();
            if (croppedBytes.length == 0) {
                throw new ExternalServiceException("图片流为空");
            }
            if (originalImageBytes == null || originalImageBytes.length == 0) {
                return recognizeImage(new ByteArrayInputStream(croppedBytes), categoryCode);
            }
            if (mockEnabled) {
                log.info("AI 识别 Mock 已启用，返回模拟识别结果");
                return buildMockLabels();
            }

            String userPrompt = DUAL_IMAGE_NOTE + buildUserPrompt(categoryCode);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", SYSTEM_PROMPT),
                    OpenAiChatMessage.multiVision("user", userPrompt, List.of(
                        Base64.getEncoder().encodeToString(croppedBytes),
                        Base64.getEncoder().encodeToString(originalImageBytes)
                    ))
                ))
                .temperature(0.3)
                .maxTokens(4096)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            String json = executeChat(request, "AI 识别（双图）");
            return parseLabels(json);
        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new ExternalServiceException("读取图片流失败", e);
        }
    }

    /**
     * 双图识别说明（前缀注入用户提示词）：声明两张图的分工，防止 AI 用裁剪图硬猜文字。
     */
    private static final String DUAL_IMAGE_NOTE = """
        本次提供两张图片：第一张是产品主体裁剪图，第二张是原始上传图。
        - 风格、六维形态、颜色、场景等视觉特征以第一张裁剪图为准；
        - OCR 文字信息（品名、型号、尺寸、价格、工厂等）优先从第二张原图中提取
          （裁剪图可能已裁掉文字区域，严禁因裁剪图无文字就判定图上无文字）。

        """;

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
            "A", "一字型",
            "B", "高靠背",
            "C", "无扶手",
            "D", "金属框架底座",
            "E", "皮革",
            "F", "光面软包"
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
     * 并按品类码注入对应的六维标签维度定义与六维枚举约束。
     *
     * @param categoryCode 产品品类码
     * @return 完整的用户提示词
     */
    private String buildUserPrompt(String categoryCode) {
        String styleEnum = buildEnumText("style");
        String sceneEnum = buildEnumText("scene");
        String materialEnum = buildEnumText("material");
        String fabricEnum = buildEnumText("fabric");
        String sixDimDescription = sixDimSchemaService.buildPromptDescription(categoryCode);
        String sixDimEnum = buildSixDimEnumPrompt(categoryCode);
        return USER_PROMPT_TEMPLATE.formatted(styleEnum, sixDimDescription + sixDimEnum,
            styleEnum, sceneEnum, materialEnum, fabricEnum);
    }

    /**
     * 构建六维标签枚举约束文本（P1 枚举化）。
     *
     * <p>按品类从 category_dict 读取 six_dim_A~D/F 字典（parent_code = 品类码），
     * 每个枚举值只注入「中文名（一句话锚点）」控制 token 成本：锚点取自 remark（视觉判别要点），
     * 超过 20 字时截到首个分句。完整判别要点与 aliases 留在字典，不进 prompt。
     * E 维度（表面材质）不建独立枚举，提示 AI 从材质/面料枚举中选择。
     * 品类无六维字典（如 GENERIC）时返回空串，prompt 行为与枚举化前一致。</p>
     *
     * @param categoryCode 产品品类码
     * @return 枚举约束文本，无字典时返回空串
     */
    private String buildSixDimEnumPrompt(String categoryCode) {
        if (categoryCode == null || categoryCode.isBlank()) {
            return "";
        }
        var schema = sixDimSchemaService.getSchema(categoryCode);
        StringBuilder sb = new StringBuilder();
        for (String dim : List.of("A", "B", "C", "D", "F")) {
            List<CategoryDict> entries;
            try {
                List<CategoryDict> all = dictService.listByType("six_dim_" + dim);
                if (all == null) {
                    continue;
                }
                entries = all.stream()
                    .filter(d -> categoryCode.equalsIgnoreCase(d.getParentCode() == null ? "" : d.getParentCode()))
                    .sorted(java.util.Comparator.comparingInt(d -> d.getSortOrder() == null ? 0 : d.getSortOrder()))
                    .toList();
            } catch (Exception e) {
                log.warn("读取六维字典枚举失败，跳过该维度枚举注入，dim={}", dim, e);
                continue;
            }
            if (entries.isEmpty()) {
                continue;
            }
            String label = schema.dims().containsKey(dim) ? schema.dims().get(dim).label() : dim;
            String enums = entries.stream()
                .map(d -> d.getDictName() + anchorOf(d.getRemark()))
                .collect(Collectors.joining("、"));
            sb.append(dim).append(" ").append(label).append("：").append(enums).append("\n");
        }
        if (sb.length() == 0) {
            return "";
        }
        return """
            六维标签枚举约束（保证输出一致、可统计，务必遵守）：
            A~D、F 每个维度必须从下列对应枚举中精确选择一项，只输出枚举中文名（不要带括号锚点）；确实无法归入任何一项时输出 "其他"。
            E 维度请从上方的材质/面料枚举中选择。
            """ + sb;
    }

    /**
     * 从 remark（视觉判别要点）提取一句话锚点：不超过 20 字直接使用，
     * 超过则截到首个分句（仍超长再硬截 20 字），控制 prompt token 成本。
     */
    private String anchorOf(String remark) {
        if (remark == null || remark.isBlank()) {
            return "";
        }
        String anchor = remark.trim();
        if (anchor.length() > 20) {
            int cut = anchor.indexOf('，');
            if (cut > 0) {
                anchor = anchor.substring(0, cut);
            }
            if (anchor.length() > 20) {
                anchor = anchor.substring(0, 20);
            }
        }
        return "（" + anchor + "）";
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
            case "fabric" -> DEFAULT_FABRIC_ENUM;
            default -> "";
        };
    }

    /**
     * PDF 页面产品区域检测提示词。
     * 要求 AI 对连续的多张 PDF 页面图片逐页分析，输出产品位置框和页面类型。
     */
    private static final String PAGE_DETECTION_SYSTEM_PROMPT = """
        你是家具产品目录分析专家。请对用户提供的一系列 PDF 页面图片逐页分析，
        判断每页类型并输出页面中每个产品图的位置框（bbox）。
        产品图在画册中通常是带白色或纯色底板的矩形图片，bbox 必须对齐该产品图
        图块的矩形边缘（包含整块底板），宁可略大也不可切断产品的任何部分，
        但不得包含说明文字、尺寸标注线、页眉页脚等无关内容。
        同时，每个产品图旁边通常配有品名、型号、尺寸、价格等说明文字，
        这些文字不属于产品图（不要框进 bbox），但必须完整提取到 nearbyText 中。
        只输出 JSON 数组，不要任何其他文字说明。
        """;

    private static final String PAGE_DETECTION_USER_PROMPT_TEMPLATE = """
        下面是 %d 张连续的 PDF 页面图片，请按顺序逐页分析。

        对每一页，判断其类型并输出产品中每个产品的位置信息：
        - pageType: product（产品页）/ cover（封面）/ toc（目录）/ separator（分隔页）/ blank（空白页）/ unknown（未知）
        - products: 当 pageType=product 时，列出该页中所有产品图的位置框、预估品类码和产品旁的说明文字

        bbox 使用相对于页面宽高的比例坐标（0.0 ~ 1.0）：
        {"x": 左上角 x, "y": 左上角 y, "w": 宽度, "h": 高度}

        bbox 规则（必须严格遵守）：
        - bbox 框住的是"产品图图块"：对齐产品图片的矩形边缘，包含图片的白色/纯色底板，
          不要按产品轮廓贴身裁剪（底板白边后续会自动去除）
        - 画册常见"产品图在下、品名/尺寸文字在上（或下）"的卡片版式：文字行不属于图块，
          bbox 必须停在产品图的底边/顶边，不得把卡片上的文字行框进来
        - 绝不可切断产品的任何部分，拿不准时宁可多包含一些底板
        - 白色/浅色产品置于浅色背景上（低对比）时最容易框小：框必须包含产品的完整
          外轮廓和底部阴影区，看不清边界时一律宁大勿小，严禁按可见边缘裁小
          （框偏大后续会自动收边，框偏小会把产品切残）
        - 不得包含产品名称、价格、参数说明等任何文字，不得包含尺寸标注线、装饰元素、
          页眉页脚和页边距，不得包含相邻图片的任何部分
        - 每个产品图独立一个框：禁止把多个产品图合并为一个框，也禁止把一个产品图拆成多个框
        - 页面中有多个产品图时必须全部列出，不得遗漏；一个都没有时 products 输出空数组
        - 产品图几乎占满整页时，给出接近整页的框是允许的
        - 坐标必须满足 0<=x、0<=y、x+w<=1、y+h<=1

        imageKind 规则（每个产品必须标注）：
        - standalone：单品图——白底或纯色/摄影棚背景的产品拍摄图，画面主体只有产品本身
        - scene：场景图/效果图——产品置于房间、展厅等真实或渲染环境中，画面含墙面、地面、
          窗帘、装饰品等环境元素。场景中完整可见、且旁边配有该产品品名/型号/尺寸等说明文字的
          产品，标注 scene 并提取 nearbyText，但 bbox 必须只框与说明文字对应的那一个产品的
          局部区域，禁止把整张场景图作为一个产品框；场景中没有对应说明文字的产品不需要框出
        - 无法确定时填 standalone

        nearbyText 规则（每个产品都要尽力提取，实在没有对应文字时输出 null）：
        - 只提取紧邻该产品图、明显描述该产品的文字，不要把其他产品或页眉页脚的文字混入
        - productName: 产品名称/品名；modelNumber: 型号/货号；dimensionText: 尺寸原文（如 2450×900×850mm）
        - priceText: 价格原文（如 ¥12800）；materialDescription: 材质描述原文
        - rawText: 该产品旁所有说明文字按原文完整输出，不要遗漏
        - 提取不到的单项填 null，不要编造

        预估品类码必须从以下枚举中精确选择，无法判断时填 null：
        %s

        输出必须是一个 JSON 数组，数组长度严格等于 %d（图片数量），第 i 个元素对应第 i 张图片：
        [
          {
            "pageType": "product",
            "products": [
              {
                "bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5},
                "estimatedCategory": "SF",
                "imageKind": "standalone",
                "nearbyText": {
                  "productName": "兰卡沙发",
                  "modelNumber": "LK-2450",
                  "dimensionText": "2450×900×850mm",
                  "priceText": "¥12800",
                  "materialDescription": "头层牛皮+实木框架",
                  "rawText": "兰卡沙发 LK-2450 2450×900×850mm ¥12800 头层牛皮+实木框架"
                }
              }
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
                byte[] bytes;
                try (stream) {
                    bytes = stream.readAllBytes();
                }
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
                .maxTokens(12288)
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

    /**
     * 品类判定提示词：录入时用户未指定品类时，用原图（含品名/规格文字版面）判定品类。
     */
    private static final String CATEGORY_CLASSIFY_SYSTEM_PROMPT = """
        你是家具品类分类专家。根据图片判断产品所属品类；图片中可能带有产品名称、型号、
        规格等文字版面，文字信息（如品名明确写了品类名）优先于外观猜测。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String CATEGORY_CLASSIFY_USER_PROMPT = """
        请判断图中家具产品的品类。
        品类码必须从以下枚举中精确选择一个：
        %s

        输出格式：{"categoryCode": "FC"}
        实在无法判断时输出 {"categoryCode": null}
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String CATEGORY_SHADOW_USER_PROMPT = """
        请对图中家具做旁路分类，不要受现有正式分类结果影响。
        一级品类码只能从以下枚举中选择：
        %s

        二级产品类型只能从以下枚举中选择；无法可靠细分时 productType 输出 null：
        %s

        输出格式：
        {"categoryCode":"DK","productType":"WRITING_DESK","confidence":"high|mid|low","reason":"一句话视觉依据"}
        只输出 JSON，不要任何其他文字说明。
        """;

    /**
     * 轻量品类判定（best-effort）：从品类字典枚举中为图片选择一个品类码。
     *
     * <p>仅返回字典中真实存在的码；Mock 模式、字典为空、AI 异常、输出无法解析或
     * 输出码不在字典中时一律返回 null，由调用方回退默认品类，绝不影响录入主流程。</p>
     *
     * @param imageStream 图片流（建议传未裁剪的原图，文字版面对判定帮助最大）
     * @return 品类码（如 FC）；无法判定时返回 null
     */
    public String classifyCategory(InputStream imageStream) {
        try (imageStream) {
            byte[] imageBytes = imageStream.readAllBytes();
            if (imageBytes.length == 0) {
                return null;
            }
            if (mockEnabled) {
                log.info("AI Mock 已启用，跳过品类判定");
                return null;
            }
            String enumText = buildCategoryEnumText();
            if (!StringUtils.hasText(enumText)) {
                return null;
            }

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", CATEGORY_CLASSIFY_SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", CATEGORY_CLASSIFY_USER_PROMPT.formatted(enumText), base64)
                ))
                .temperature(0.1)
                .maxTokens(128)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            String json = executeChat(request, "品类判定");
            return parseCategoryCode(json);
        } catch (Exception e) {
            log.warn("品类判定失败，返回 null：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 使用全量字典执行扩展品类旁路判定。该方法只返回预测，不修改任何业务数据。
     *
     * @param imageStream         原始产品图片
     * @param allowedCategoryCodes 本次 Shadow 允许的旧类与扩展类代码集合
     * @param productTypes        启用的二级产品类型知识
     * @return 校验后的旁路预测；无法判定或模型输出越界时返回 null
     */
    public CategoryShadowPrediction classifyCategoryShadow(
        InputStream imageStream,
        Set<String> allowedCategoryCodes,
        List<KnowledgeProductType> productTypes
    ) {
        try (imageStream) {
            byte[] imageBytes = imageStream.readAllBytes();
            if (imageBytes.length == 0 || mockEnabled || allowedCategoryCodes == null || allowedCategoryCodes.isEmpty()) {
                return null;
            }

            Set<String> allowed = allowedCategoryCodes.stream()
                .filter(StringUtils::hasText)
                .map(code -> code.trim().toUpperCase())
                .collect(Collectors.toCollection(LinkedHashSet::new));
            List<CategoryDict> categories = dictService.listAllByType("category").stream()
                .filter(dict -> "active".equalsIgnoreCase(dict.getStatus()) || dict.getStatus() == null)
                .filter(dict -> dict.getDictCode() != null && allowed.contains(dict.getDictCode().toUpperCase()))
                .toList();
            if (categories.isEmpty()) {
                return null;
            }

            String categoryEnum = categories.stream()
                .map(dict -> dict.getDictCode() + "(" + dict.getDictName() + ")")
                .collect(Collectors.joining("、"));
            List<KnowledgeProductType> allowedTypes = productTypes == null ? List.of() : productTypes.stream()
                .filter(type -> allowed.contains(type.getBusinessCategoryCode()))
                .toList();
            String typeEnum = allowedTypes.stream()
                .map(type -> type.getTypeCode() + "(" + type.getTypeName() + ",品类="
                    + type.getBusinessCategoryCode() + ",别名=" + type.getAliases() + ")")
                .collect(Collectors.joining("、"));
            if (!StringUtils.hasText(typeEnum)) {
                typeEnum = "无";
            }

            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", CATEGORY_CLASSIFY_SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", CATEGORY_SHADOW_USER_PROMPT.formatted(categoryEnum, typeEnum),
                        Base64.getEncoder().encodeToString(imageBytes))
                ))
                .temperature(0.1)
                .maxTokens(256)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            Map<?, ?> result = objectMapper.readValue(executeChat(request, "扩展品类 Shadow 判定"), Map.class);
            String categoryCode = normalizeText(result.get("categoryCode"));
            if (categoryCode == null || !allowed.contains(categoryCode)) {
                return null;
            }
            boolean knownCategory = categories.stream()
                .anyMatch(dict -> categoryCode.equalsIgnoreCase(dict.getDictCode()));
            if (!knownCategory) {
                return null;
            }

            String productTypeCandidate = normalizeText(result.get("productType"));
            String productType = productTypeCandidate;
            if (productTypeCandidate != null) {
                boolean validType = allowedTypes.stream().anyMatch(type ->
                    productTypeCandidate.equalsIgnoreCase(type.getTypeCode())
                        && categoryCode.equalsIgnoreCase(type.getBusinessCategoryCode()));
                if (!validType) {
                    log.warn("Shadow 模型输出非法或跨品类 product_type，已清空: category={}, type={}",
                        categoryCode, productType);
                    productType = null;
                }
            }
            return new CategoryShadowPrediction(
                categoryCode,
                productType,
                normalizeText(result.get("confidence")),
                result.get("reason") instanceof String reason ? reason.trim() : null
            );
        } catch (Exception e) {
            log.warn("扩展品类 Shadow 判定失败，忽略旁路结果: {}", e.getMessage());
            return null;
        }
    }

    private String normalizeText(Object value) {
        if (!(value instanceof String text) || !StringUtils.hasText(text)) {
            return null;
        }
        return text.trim().toUpperCase();
    }

    /** 解析品类判定结果；码不在字典中时返回 null（防 AI 编造枚举外的码）。 */
    private String parseCategoryCode(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            Object code = map.get("categoryCode");
            if (!(code instanceof String text) || text.isBlank()) {
                return null;
            }
            String normalized = text.trim().toUpperCase();
            boolean exists = dictService.listByType("category").stream()
                .anyMatch(d -> normalized.equalsIgnoreCase(d.getDictCode()));
            if (!exists) {
                log.warn("AI 判定的品类码不在字典中，忽略: {}", normalized);
                return null;
            }
            return normalized;
        } catch (Exception e) {
            log.warn("解析品类判定结果失败，json={}", json, e);
            return null;
        }
    }

    /**
     * 单图产品主体检测提示词。
     * 要求 AI 完整包围图中最完整的家具产品（所有部件不可切断），排除搭配品、装饰、文字等干扰。
     * 选择标准是"完整度最高"而非"面积最大"：多个产品并存时，优先选部件完整可见、未被画面边缘
     * 切断、遮挡最少的那个，即使它不是图中最大的产品。
     */
    private static final String SUBJECT_DETECTION_SYSTEM_PROMPT = """
        你是家具产品图片分析专家。请找出图片中最完整的家具产品（完整度最高、而非面积最大），
        输出其完整包围框（bbox）。bbox 必须完整包含产品的所有部件，
        宁可多带少量背景边距，也绝不可切断产品的任何部分；
        但不得包含搭配产品、装饰品、绿植、文字、水印。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String SUBJECT_DETECTION_USER_PROMPT = """
        请分析这张图片，找出图中最完整的家具产品，输出它的完整包围框。

        bbox 使用相对于图片宽高的千分比整数坐标（0 ~ 1000）：
        [x1, y1, x2, y2] = [左上角 x, 左上角 y, 右下角 x, 右下角 y]

        bbox 规则（必须严格遵守）：
        - 只框一个家具产品；图片中有多个产品/搭配品时，选择"完整度最高"的那个：
          所有部件（腿、脚、扶手、靠背、装饰性突出物）完整可见、未被画面边缘切断、被遮挡最少
        - 严禁仅凭面积大小选择：最大的产品若被切断/遮挡严重，应改选更完整的那个
        - 必须完整包含所选产品的所有部件，一个都不能少
        - 常见错误（严禁出现）：切断椅腿/沙发脚、截掉靠背顶部、漏掉扶手、把产品的任何部件框在框外
        - 框内允许带少量背景边距，宁可略大也绝不可切断产品的任何部分
        - 不得包含搭配产品、装饰品、绿植、地毯、文字、水印
        - 坐标必须满足 0<=x1<x2<=1000、0<=y1<y2<=1000
        - 如果图中没有明确的家具产品主体，bbox 输出 null

        输出格式：
        {"bbox": [120, 50, 880, 950]}
        只输出 JSON，不要任何其他文字说明。
        """;

    /**
     * 检测单张图片中的产品主体位置。
     *
     * <p>用于录入时自动裁剪主图：AI 框出最主要的家具产品，排除搭配品/装饰/文字。
     * Mock 模式下直接返回 null（不裁剪）。</p>
     *
     * @param imageStream 图片流
     * @return 产品主体 bbox（比例坐标）；无明确主体时返回 null
     */
    public ProductBoundingBox detectProductSubject(InputStream imageStream) {
        try (imageStream) {
            byte[] imageBytes = imageStream.readAllBytes();
            if (imageBytes.length == 0) {
                throw new ExternalServiceException("图片流为空");
            }

            if (mockEnabled) {
                log.info("AI Mock 已启用，跳过产品主体检测");
                return null;
            }

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", SUBJECT_DETECTION_SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", SUBJECT_DETECTION_USER_PROMPT, base64)
                ))
                .temperature(0.2)
                .maxTokens(1024)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            String json = executeChat(request, "产品主体检测");
            return parseSubjectBbox(json);
        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new ExternalServiceException("读取图片流失败", e);
        }
    }

    /**
     * 解析产品主体检测结果，兼容两种格式：
     * 千分比 xyxy 数组 {@code [x1,y1,x2,y2]}（0~1000，新提示词格式）与
     * 浮点 xywh 对象 {@code {"x","y","w","h"}}（0.0~1.0，旧格式）。
     * AI 输出不规范时返回 null，由调用方回退原图。
     */
    @SuppressWarnings("unchecked")
    private ProductBoundingBox parseSubjectBbox(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object bbox = map.get("bbox");
            if (bbox instanceof List<?> xyxy) {
                return parseXyxyBbox(xyxy);
            }
            return parseBoundingBox(bbox);
        } catch (Exception e) {
            log.warn("解析产品主体检测结果失败，json={}", json, e);
            return null;
        }
    }

    /** 解析千分比 xyxy 数组坐标并转为比例 xywh。 */
    private ProductBoundingBox parseXyxyBbox(List<?> xyxy) {
        if (xyxy.size() != 4) {
            return null;
        }
        try {
            double x1 = parseDoubleValue(xyxy.get(0));
            double y1 = parseDoubleValue(xyxy.get(1));
            double x2 = parseDoubleValue(xyxy.get(2));
            double y2 = parseDoubleValue(xyxy.get(3));
            // 千分比整数坐标（0~1000）归一化；若模型直接给了 0~1 浮点则直接使用
            double scale = Math.max(Math.max(x1, y1), Math.max(x2, y2)) > 1.5 ? 1000.0 : 1.0;
            ProductBoundingBox bbox = new ProductBoundingBox(
                x1 / scale, y1 / scale, (x2 - x1) / scale, (y2 - y1) / scale);
            return bbox.isValid() ? bbox : null;
        } catch (Exception e) {
            log.warn("解析 xyxy bbox 失败：{}", xyxy, e);
            return null;
        }
    }

    /**
     * 产品完整性校验提示词：判断裁剪图中产品是否有部件被切断。
     */
    private static final String COMPLETE_CHECK_SYSTEM_PROMPT = """
        你是家具产品图片质检专家。请判断图片中的家具产品是否完整。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String COMPLETE_CHECK_USER_PROMPT = """
        请判断这张图片中的家具产品是否完整。

        判定规则：
        - 只有当产品的部件（腿、脚、扶手、靠背、顶部、边缘）明显被图片边界切断时，才判为不完整
        - 产品完整、或无法确定时，一律判为完整
        - 图片中是否包含背景、搭配品不影响完整性判断

        输出格式：
        {"complete": true}
        只输出 JSON，不要任何其他文字说明。
        """;

    /**
     * 校验图片中的家具产品是否完整（无明显被切断的部件）。
     *
     * <p>用于主图裁剪后的质量校验。AI 调用失败、输出不规范或 Mock 模式时返回 true
     * （宁可信任裁剪结果，不误杀）。</p>
     *
     * @param imageStream 裁剪后的图片流
     * @return 产品是否完整
     */
    @SuppressWarnings("unchecked")
    public boolean isProductComplete(InputStream imageStream) {
        try (imageStream) {
            byte[] imageBytes = imageStream.readAllBytes();
            if (imageBytes.length == 0 || mockEnabled) {
                return true;
            }

            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", COMPLETE_CHECK_SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", COMPLETE_CHECK_USER_PROMPT, base64)
                ))
                .temperature(0.1)
                .maxTokens(256)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();

            String json = executeChat(request, "产品完整性校验");
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object complete = map.get("complete");
            if (complete instanceof Boolean b) {
                return b;
            }
            return complete == null || Boolean.parseBoolean(complete.toString());
        } catch (Exception e) {
            log.warn("产品完整性校验失败，默认视为完整：{}", e.getMessage());
            return true;
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
                    pp.setBbox(parseBoundingBoxClamped(pm.get("bbox")));
                    if (pp.getBbox() == null) {
                        log.warn("产品 bbox 解析失败（模型未输出合法位置框），raw={}", pm.get("bbox"));
                    }
                    Object category = pm.get("estimatedCategory");
                    pp.setEstimatedCategory(category != null ? category.toString() : null);
                    Object imageKind = pm.get("imageKind");
                    pp.setImageKind(imageKind != null ? imageKind.toString() : null);
                    pp.setNearbyText(parseNearbyText(pm.get("nearbyText")));
                    pageProducts.add(pp);
                }
            }
            region.setProducts(pageProducts);
        }
        return region;
    }

    /**
     * 解析产品旁的说明文字。AI 输出不规范（非对象/字段类型异常）时返回 null，不影响 bbox 主流程。
     */
    private OcrResult parseNearbyText(Object raw) {
        if (!(raw instanceof Map<?, ?>)) {
            return null;
        }
        try {
            return objectMapper.convertValue(raw, OcrResult.class);
        } catch (Exception e) {
            log.warn("解析 nearbyText 失败，忽略该产品文字", e);
            return null;
        }
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

    /**
     * 解析页面检测返回的 bbox，越界坐标收敛到 [0,1] 页内而不是整框丢弃。
     *
     * <p>模型偶发给出超出页面的框（如 x=0.48、w=0.87，实测整批集体出现），
     * 严格校验会把整批产品全部丢弃；页面检测路径宁可收敛后裁剪。
     * 与 {@link #parseBoundingBox}（主体检测等严格场景）区分开。</p>
     */
    private ProductBoundingBox parseBoundingBoxClamped(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return null;
        }
        try {
            double x = clamp01(parseDoubleValue(map.get("x")));
            double y = clamp01(parseDoubleValue(map.get("y")));
            double w = clamp01(parseDoubleValue(map.get("w")));
            double h = clamp01(parseDoubleValue(map.get("h")));
            w = Math.min(w, 1.0 - x);
            h = Math.min(h, 1.0 - y);
            ProductBoundingBox bbox = new ProductBoundingBox(x, y, w, h);
            return bbox.isValid() ? bbox : null;
        } catch (Exception e) {
            log.warn("解析 bbox 失败", e);
            return null;
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
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
     * 户型图空间识别提示词：识别户型平面图中的功能空间，输出 roomType/bbox/尺寸标注。
     */
    private static final String FLOOR_PLAN_SYSTEM_PROMPT = """
        你是精通中国住宅 CAD 户型图制图规范的建筑识图专家。请分析这张户型平面图，识别其中的各个功能空间。

        只输出 JSON，不要任何其他文字。输出格式：
        {
          "rooms": [
            {
              "roomType": "living_room",
              "bbox": {"x": 0.12, "y": 0.30, "w": 0.40, "h": 0.35},
              "dimensionText": "4200×3800",
              "label": "客厅"
            }
          ],
          "scaleText": "1:50"
        }

        字段规则：
        1. roomType 只能从以下枚举选择：living_room(客厅) / dining_room(餐厅) / bedroom(卧室) / kitchen(厨房) / bathroom(卫生间) / balcony(阳台) / study(书房) / hallway(过道) / other
        2. bbox 为归一化坐标，x,y 为左上角，w,h 为宽高，取值范围 [0,1]，框要紧贴该空间的墙体边界
        3. 每个闭合空间单独一个框，不得合并多个空间，不得遗漏任何闭合空间（含阳台、卫生间等小空间）
        4. 图上如有比例尺标注（如 "1:50"、"1:100"），原样提取到 scaleText，没有则为 null

        空间边界判定（严格遵守）：
        1. 忽略图框、标题栏、图例、装订线、外部尺寸链等一切非户型本体元素；它们不是空间，不得框出，也不得作为定位参照
        2. 只有墙体（含填充墙图案，通常为粗黑线或斜线填充的厚线）围合出的闭合区域才是空间边界；家具、洁具、橱柜、植物的轮廓线不是空间边界
        3. bbox 必须紧贴墙体内侧（净空间），不得把墙体厚度、外部尺寸链或相邻空间框进来
        4. 输出每个 bbox 前先核对：它的四条边必须都落在墙体上；如果某条边落在床、沙发、桌椅、洁具等家具边缘上，说明框小了，必须继续向外延伸到墙体。只框住家具或空间中央一小块是典型错误

        dimensionText 输出契约（严格遵守，只允许两种结果）：
        A. 两个纯数字用 × 连接，单位一律为毫米，不带任何单位字符，顺序为"水平方向尺寸×垂直方向尺寸"，如 "4200×3800"；
        B. null —— 没有可靠依据时必须输出 null。

        尺寸识读方法（CAD 制图常识）：
        1. 尺寸标注多沿墙体外侧布置，呈尺寸链形式分段标注（如 1200+2400+900），房间净尺寸常需将同向连续分段相加，或用轴线尺寸减去墙厚得到
        2. 房间内可能直接标注尺寸，形式多样（如 "4200×3800"、"4.2m×3.8m"、"420cm×380cm" 或仅一个方向的单值），统一换算成毫米后按契约 A 输出（4.2m→4200，380cm→3800）
        3. 只有单向尺寸、另一方向无法从尺寸链或轴线尺寸可靠推算时，dimensionText 输出 null，不要只填一个方向
        4. 每个尺寸必须能在图上找到明确数字依据；dimensionText 只写最终毫米结果，不要写计算过程

        严禁事项（防幻觉，违反即为错误输出）：
        1. 没有可靠数字依据时严禁编造尺寸，必须输出 null
        2. 严禁把门窗洞口宽度（常见 700/800/900/1000）、墙体厚度（常见 100/200/240）、家具电器尺寸当作房间尺寸
        3. 严禁输出面积（如 "12.5㎡"）代替尺寸
        4. 严禁输出带单位的字符串（如 "4200mm×3800mm"、"4.2m×3.8m"），dimensionText 只能是纯数字两数相连或 null

        示例：
        示例1（房间内有标注）：图中客厅内标注 "4.2m×3.8m"，换算为毫米后输出
        {"roomType": "living_room", "bbox": {"x": 0.10, "y": 0.28, "w": 0.42, "h": 0.36}, "dimensionText": "4200×3800", "label": "客厅"}
        示例2（无标注空间）：主卧内无任何尺寸标注，外侧尺寸链也无法对应，输出
        {"roomType": "bedroom", "bbox": {"x": 0.55, "y": 0.60, "w": 0.30, "h": 0.28}, "dimensionText": null, "label": "主卧"}
        示例3（尺寸链推算）：厨房水平方向外侧尺寸链为 1200+2100 两段，相加得 3300，垂直方向标注 2400，输出
        {"roomType": "kitchen", "bbox": {"x": 0.60, "y": 0.08, "w": 0.22, "h": 0.18}, "dimensionText": "3300×2400", "label": "厨房"}
        （若尺寸链与墙体的对应关系不明确，则该方向不得推算，dimensionText 输出 null）

        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String FLOOR_PLAN_USER_PROMPT =
        "请分析这张户型平面图，识别其中的各个功能空间并输出 JSON。";

    /** 网格叠加说明：仅在 {@link #GRID_OVERLAY_ENABLED} 开启时注入用户提示词。 */
    private static final String GRID_OVERLAY_NOTE =
        "\n提示：图上叠加了浅灰色 10% 间隔坐标网格（边缘有 0.1~0.9 刻度字），可用于辅助定位 bbox 坐标；网格线不是墙体，不得当作空间边界。";

    /** 是否在空间识别图上叠加 10% 间隔坐标网格（实测开关：变差则改为 false）。 */
    private static final boolean GRID_OVERLAY_ENABLED = true;

    /**
     * 户型本体区域检测提示词（两阶段识别第一阶段）：
     * 框出墙体围合的居住空间区域，排除图框/标题栏/图例/装订线/外部尺寸链/空白边距。
     */
    private static final String FLOOR_PLAN_REGION_SYSTEM_PROMPT = """
        你是精通中国住宅 CAD 图纸的建筑识图专家。请在整张图纸图片中定位"户型图本体"区域。

        户型图本体 = 由墙体围合出的居住空间平面区域（含房间内家具布置与房间内文字标注）。
        必须排除：图纸外框边框线、底部标题栏（工程名称/设计/制图等表格）、左下角图例表、
        左侧装订线、墙体外侧的尺寸链标注（外墙尺寸数字与引线）、四周空白边距。

        bbox 使用相对于整张图片宽高的归一化坐标（0.0 ~ 1.0）：
        {"bbox": {"x": 左上角x, "y": 左上角y, "w": 宽度, "h": 高度}}

        规则：
        - bbox 紧贴最外层墙体外边缘，不多带尺寸链与空白，也不切断任何墙体
        - 图中没有户型平面图时输出 {"bbox": null}
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String FLOOR_PLAN_REGION_USER_PROMPT =
        "请定位这张图纸中户型图本体区域，输出 JSON。";

    /** 户型本体区域合法性下限/上限（占原图面积比例）：超出范围视为 AI 误判，回退原图。 */
    private static final double REGION_MIN_AREA_RATIO = 0.25;
    private static final double REGION_MAX_AREA_RATIO = 0.95;

    /** 裁剪图送识别前的最小宽度（px）：低于该值按比例放大，避免细墙线在小图下不可辨。 */
    private static final int CROP_UPSCALE_MIN_WIDTH = 2000;

    /**
     * 户型本体裁剪外扩边距（归一化坐标，四边各外扩该值并钳制到 [0,1]）。
     * AI 框出的本体区域紧贴外墙，直接裁剪会把贴墙尺寸链裁掉导致尺寸幻觉；
     * 外扩 4% 保住墙体外侧的尺寸标注，坐标映射基于外扩后的裁剪框，逻辑不变。
     */
    private static final double CROP_MARGIN_RATIO = 0.04;

    /**
     * 将户型本体区域四边外扩指定比例（归一化坐标），钳制到 [0,1]。
     *
     * @param region      AI 检测的户型本体区域
     * @param marginRatio 四边各外扩的归一化比例
     * @return 外扩后的区域
     */
    static ProductBoundingBox expandRegion(ProductBoundingBox region, double marginRatio) {
        double x = clamp01(region.getX() - marginRatio);
        double y = clamp01(region.getY() - marginRatio);
        double w = Math.min(clamp01(region.getWidth() + 2 * marginRatio), 1.0 - x);
        double h = Math.min(clamp01(region.getHeight() + 2 * marginRatio), 1.0 - y);
        return new ProductBoundingBox(x, y, w, h);
    }

    /** 按目标宽度等比放大图片（双线性平滑）。 */
    static BufferedImage upscale(BufferedImage source, int targetWidth) {
        int targetHeight = Math.max(1, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
    }

    /**
     * 检测户型图本体区域（两阶段识别第一阶段）。
     *
     * <p>一次轻量 AI 调用返回户型本体（墙体围合的居住空间区域）的归一化 bbox，
     * 排除标题栏/图框边框/图例/装订线/外部尺寸链/空白边距。
     * Mock 模式、AI 异常或输出不合法时返回 null，由调用方回退原图。</p>
     *
     * @param imageBytes 户型图原图字节
     * @return 户型本体区域 bbox（归一化）；不可用时返回 null
     */
    public ProductBoundingBox detectFloorPlanRegion(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0 || mockEnabled) {
            return null;
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            OpenAiChatRequest request = OpenAiChatRequest.builder()
                .model(model)
                .messages(List.of(
                    OpenAiChatMessage.text("system", FLOOR_PLAN_REGION_SYSTEM_PROMPT),
                    OpenAiChatMessage.vision("user", FLOOR_PLAN_REGION_USER_PROMPT, base64,
                        floorPlanMinPixels, floorPlanMaxPixels)
                ))
                .temperature(0.1)
                .maxTokens(512)
                .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
                .build();
            String json = executeChat(request, "户型图本体区域检测");
            return parseSubjectBbox(json);
        } catch (Exception e) {
            log.warn("户型图本体区域检测失败，回退整图识别：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 户型本体区域合法性判定：bbox 合法且面积占原图 25%~95% 才采信，
     * 防止 AI 把标题栏/整张图误判为本体。
     */
    static boolean isPlausibleFloorPlanRegion(ProductBoundingBox region) {
        if (region == null || !region.isValid()) {
            return false;
        }
        double area = region.getWidth() * region.getHeight();
        return area >= REGION_MIN_AREA_RATIO && area <= REGION_MAX_AREA_RATIO;
    }

    /**
     * 把裁剪图内的归一化 bbox 映射回原图坐标。
     */
    static ProductBoundingBox mapToOriginalCoords(ProductBoundingBox roomBox, ProductBoundingBox region) {
        double x = region.getX() + roomBox.getX() * region.getWidth();
        double y = region.getY() + roomBox.getY() * region.getHeight();
        double w = roomBox.getWidth() * region.getWidth();
        double h = roomBox.getHeight() * region.getHeight();
        ProductBoundingBox mapped = new ProductBoundingBox(
            clamp01(x), clamp01(y), clamp01(w), clamp01(h));
        return mapped.isValid() ? mapped : null;
    }

    /**
     * 按区域裁剪原图并编码为 PNG；图片无法解码时返回 null（调用方回退原图）。
     */
    static byte[] cropRegionToPng(byte[] imageBytes, ProductBoundingBox region) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                return null;
            }
            int sx = (int) Math.round(region.getX() * source.getWidth());
            int sy = (int) Math.round(region.getY() * source.getHeight());
            int sw = (int) Math.round(region.getWidth() * source.getWidth());
            int sh = (int) Math.round(region.getHeight() * source.getHeight());
            sx = Math.max(0, Math.min(sx, source.getWidth() - 1));
            sy = Math.max(0, Math.min(sy, source.getHeight() - 1));
            sw = Math.max(1, Math.min(sw, source.getWidth() - sx));
            sh = Math.max(1, Math.min(sh, source.getHeight() - sy));
            BufferedImage cropped = source.getSubimage(sx, sy, sw, sh);
            // 裁剪图过小时放大：细墙线在小分辨率下易被模型忽略，放大提升墙体可见性
            if (cropped.getWidth() < CROP_UPSCALE_MIN_WIDTH) {
                cropped = upscale(cropped, CROP_UPSCALE_MIN_WIDTH);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("户型图本体裁剪失败，回退整图识别：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 在图上叠加浅色 10% 间隔坐标网格与边缘刻度小字（0.1~0.9），辅助 AI 定位 bbox。
     * 线宽 1px、低透明度，不遮盖图纸线条。
     */
    static BufferedImage overlayCoordinateGrid(BufferedImage source) {        int w = source.getWidth();
        int h = source.getHeight();
        BufferedImage overlaid = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = overlaid.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setStroke(new BasicStroke(1f));
            g.setColor(new Color(150, 150, 150, 70));
            for (int i = 1; i < 10; i++) {
                int x = Math.round(w * i / 10f);
                int y = Math.round(h * i / 10f);
                g.drawLine(x, 0, x, h);
                g.drawLine(0, y, w, y);
            }
            g.setColor(new Color(110, 110, 110, 160));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(10, Math.min(w, h) / 60)));
            for (int i = 1; i < 10; i++) {
                String label = "0." + i;
                int x = Math.round(w * i / 10f);
                int y = Math.round(h * i / 10f);
                g.drawString(label, x + 2, g.getFontMetrics().getHeight() + 2);
                g.drawString(label, 2, y - 2);
            }
        } finally {
            g.dispose();
        }
        return overlaid;
    }

    /**
     * 识别户型平面图中的功能空间。
     *
     * <p>两阶段识别（精度提升）：先 {@link #detectFloorPlanRegion} 定位户型本体区域
     * （排除标题栏/图例/尺寸链干扰），区域合法（面积占原图 25%~95%）则裁剪出该区域
     * 并叠加坐标网格后送空间识别，返回的归一化 bbox 再映射回原图坐标；
     * 区域检测失败/不合法/图片无法解码时回退为整图识别，行为与改造前一致。</p>
     *
     * <p>解析容错沿用 PDF 链路成熟模式：去 markdown 围栏（executeChat 已处理）、
     * bbox 越界收敛（{@link #parseBoundingBoxClamped}）、rooms 缺省返回空列表不抛错；
     * 并补齐工程保险：response_format=json_object、maxTokens=8192、截断截尾修复
     * （{@link #recoverTruncatedFloorPlanRooms}，思路同 {@code parsePageRegionsStreaming}）。</p>
     *
     * @param imageBytes 户型图片字节（jpg/png）
     * @param hint       用户补充说明（如 "这是三室两厅"），可空
     * @return 空间识别结果（bbox 为相对原图的归一化坐标）
     */
    public FloorPlanDetectResult detectFloorPlanRooms(byte[] imageBytes, String hint) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ExternalServiceException("户型图片为空");
        }

        // 第一阶段：定位户型本体区域并裁剪（不合法则回退整图）
        ProductBoundingBox region = detectFloorPlanRegion(imageBytes);
        byte[] analysisImage = imageBytes;
        if (isPlausibleFloorPlanRegion(region)) {
            // 外扩 4% 再裁剪：保住贴墙尺寸链（防尺寸幻觉）；后续坐标映射基于外扩后的裁剪框
            ProductBoundingBox expanded = expandRegion(region, CROP_MARGIN_RATIO);
            byte[] cropped = cropRegionToPng(imageBytes, expanded);
            if (cropped != null) {
                analysisImage = cropped;
                region = expanded;
                log.info("户型图本体区域采信：region=[{},{},{},{}]，外扩 {} 后裁剪送识别",
                    region.getX(), region.getY(), region.getWidth(), region.getHeight(), CROP_MARGIN_RATIO);
            } else {
                region = null;
                log.warn("户型图本体裁剪失败，回退整图识别");
            }
        } else {
            region = null;
            log.info("户型图本体区域未采信（检测失败或面积越界），回退整图识别");
        }

        // 网格叠加（裁剪图或整图上）：辅助 AI 定位坐标
        if (GRID_OVERLAY_ENABLED) {
            analysisImage = overlayGridOnImage(analysisImage);
        }

        String base64 = Base64.getEncoder().encodeToString(analysisImage);
        String userPrompt = FLOOR_PLAN_USER_PROMPT;
        if (GRID_OVERLAY_ENABLED) {
            userPrompt += GRID_OVERLAY_NOTE;
        }
        if (StringUtils.hasText(hint)) {
            userPrompt += "\n用户补充说明：" + hint.trim();
        }

        OpenAiChatRequest request = OpenAiChatRequest.builder()
            .model(model)
            .messages(List.of(
                OpenAiChatMessage.text("system", FLOOR_PLAN_SYSTEM_PROMPT),
                OpenAiChatMessage.multiVision("user", userPrompt, List.of(base64),
                    floorPlanMinPixels, floorPlanMaxPixels)
            ))
            .temperature(0.2)
            .maxTokens(8192)
            .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
            .build();

        String json = executeChat(request, "户型图空间识别");
        FloorPlanDetectResult result = parseFloorPlanRooms(json);

        // 第二阶段坐标回映射：裁剪图内坐标 → 原图坐标
        if (region != null) {
            mapRoomsToOriginal(result, region);
        }
        return result;
    }

    /** 在图片字节上叠加坐标网格；解码失败时原样返回（不阻断主流程）。 */
    private static byte[] overlayGridOnImage(byte[] imageBytes) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                return imageBytes;
            }
            BufferedImage overlaid = overlayCoordinateGrid(source);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(overlaid, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("坐标网格叠加失败，使用原图：{}", e.getMessage());
            return imageBytes;
        }
    }

    /** 将识别结果中各房间 bbox 从裁剪图坐标映射回原图坐标。 */
    private static void mapRoomsToOriginal(FloorPlanDetectResult result, ProductBoundingBox region) {
        for (FloorPlanDetectResult.Room room : result.getRooms()) {
            if (room.getX() == null || room.getY() == null || room.getW() == null || room.getH() == null) {
                continue;
            }
            ProductBoundingBox mapped = mapToOriginalCoords(
                new ProductBoundingBox(room.getX(), room.getY(), room.getW(), room.getH()), region);
            if (mapped != null) {
                room.setX(mapped.getX());
                room.setY(mapped.getY());
                room.setW(mapped.getWidth());
                room.setH(mapped.getHeight());
            }
        }
    }

    /**
     * 解析户型图空间识别结果。JSON 解析失败先尝试截尾修复（rooms 数组截断时
     * 丢弃不完整的最后一个对象），恢复成功返回已解析的房间；仍失败才抛
     * {@link ExternalServiceException}。rooms 缺失/为空时返回空列表，不抛错。
     */
    private FloorPlanDetectResult parseFloorPlanRooms(String json) {
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            return buildFloorPlanResult(map);
        } catch (Exception e) {
            log.warn("户型图空间识别结果 JSON 可能截断，尝试截尾修复");
            FloorPlanDetectResult recovered = recoverTruncatedFloorPlanRooms(json);
            if (recovered != null) {
                log.info("截尾修复恢复 {} 个空间", recovered.getRooms().size());
                return recovered;
            }
            log.error("解析户型图空间识别结果失败，json={}", json, e);
            throw new ExternalServiceException("解析 AI 识别结果失败", e);
        }
    }

    /** 由完整解析的 JSON Map 构建识别结果（rooms 缺省/为空时返回空列表）。 */
    private FloorPlanDetectResult buildFloorPlanResult(Map<?, ?> map) {
        FloorPlanDetectResult result = new FloorPlanDetectResult();
        Object scaleText = map.get("scaleText");
        result.setScaleText(scaleText != null ? scaleText.toString() : null);

        List<FloorPlanDetectResult.Room> rooms = new ArrayList<>();
        Object rawRooms = map.get("rooms");
        if (rawRooms instanceof List<?> roomList) {
            for (Object r : roomList) {
                if (r instanceof Map<?, ?> rm) {
                    rooms.add(toFloorPlanRoom(rm));
                }
            }
        }
        result.setRooms(rooms);
        return result;
    }

    /** 单个房间 Map → Room 实体（bbox 越界收敛，解析失败仅坐标留空不整行丢弃）。 */
    private FloorPlanDetectResult.Room toFloorPlanRoom(Map<?, ?> rm) {
        FloorPlanDetectResult.Room room = new FloorPlanDetectResult.Room();
        room.setRoomType(toTextValue(rm.get("roomType")));
        room.setLabel(toTextValue(rm.get("label")));
        room.setDimensionText(toTextValue(rm.get("dimensionText")));
        ProductBoundingBox bbox = parseBoundingBoxClamped(rm.get("bbox"));
        if (bbox != null) {
            room.setX(bbox.getX());
            room.setY(bbox.getY());
            room.setW(bbox.getWidth());
            room.setH(bbox.getHeight());
        }
        return room;
    }

    /**
     * 截尾修复：流式读取 rooms 数组中已完整的房间对象，丢弃截断处不完整的
     * 最后一个对象。一个完整房间都读不出时返回 null（交由调用方抛错）。
     */
    @SuppressWarnings("unchecked")
    private FloorPlanDetectResult recoverTruncatedFloorPlanRooms(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        FloorPlanDetectResult result = new FloorPlanDetectResult();
        List<FloorPlanDetectResult.Room> rooms = new ArrayList<>();
        try (JsonParser parser = objectMapper.getFactory().createParser(json)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return null;
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken valueToken = parser.nextToken();
                if ("rooms".equals(field) && valueToken == JsonToken.START_ARRAY) {
                    while (parser.nextToken() == JsonToken.START_OBJECT) {
                        Map<String, Object> rm = parser.readValueAs(Map.class);
                        rooms.add(toFloorPlanRoom(rm));
                    }
                } else if ("scaleText".equals(field) && valueToken != null && valueToken.isScalarValue()) {
                    result.setScaleText(parser.getValueAsString());
                } else if (valueToken != null) {
                    parser.skipChildren();
                }
            }
        } catch (Exception e) {
            log.warn("流式解析户型图识别结果中断，已恢复 {} 个空间", rooms.size());
        }
        if (rooms.isEmpty()) {
            return null;
        }
        result.setRooms(rooms);
        return result;
    }

    private String toTextValue(Object value) {
        return value != null ? value.toString() : null;
    }

    // ========== 户型图优化二期：逐房间二次精修（refinement pass） ==========

    /** 精修裁剪外扩比例：按房间 bbox 自身宽高的 25% 四边外扩（钳制图内）。 */
    private static final double REFINE_CROP_MARGIN_RATIO = 0.25;

    /** 精修小图短边放大目标（px）：提升细墙线/尺寸小字可辨度。 */
    private static final int REFINE_UPSCALE_SHORT_EDGE = 768;

    /** 精修框与初检框面积比上限（3 倍）：超出视为精修失控，保留初检结果。 */
    private static final double REFINE_AREA_TOLERANCE = 3.0;

    /**
     * 精修框面积下限（初检的 0.8 倍）：一期实测残留问题是初检框偏小/锚家具，
     * 精修显著缩小（<0.8×）基本是进一步锚向家具的劣化，判定失控保留初检。
     */
    private static final double REFINE_MIN_AREA_RATIO = 0.8;

    /** 精修 roomType 合法枚举（与空间识别一致的 9 类）。 */
    private static final java.util.Set<String> VALID_ROOM_TYPES = java.util.Set.of(
        "living_room", "dining_room", "bedroom", "kitchen", "bathroom",
        "balcony", "study", "hallway", "other");

    private static final String FLOOR_PLAN_REFINE_SYSTEM_PROMPT = """
        你是精通中国住宅 CAD 户型图制图规范的建筑识图专家。
        只输出 JSON，不要任何其他文字说明。
        """;

    private static final String FLOOR_PLAN_REFINE_USER_PROMPT_TEMPLATE = """
        这是户型图中裁剪出的一个空间（疑似 %s）。
        请输出该空间墙体内侧边界框（相对本小图的归一化坐标）、确认后的 roomType 和 label。

        roomType 只能从以下枚举选择：living_room(客厅) / dining_room(餐厅) / bedroom(卧室) / kitchen(厨房) / bathroom(卫生间) / balcony(阳台) / study(书房) / hallway(过道) / other

        bbox 规则（严格遵守）：
        - 格式 {"x": 左上角x, "y": 左上角y, "w": 宽度, "h": 高度}，归一化取值 [0,1]，满足 0<=x、0<=y、x+w<=1、y+h<=1
        - 四条边必须都落在墙体内侧（净空间），不得包含墙体厚度，也不得把相邻空间框进来
        - 严禁按床、沙发、桌椅、洁具、橱柜等家具边缘收缩：若某条边落在家具边缘上，必须向外延伸到墙体
        - 目标空间可能延伸到裁剪图边缘（初检框常偏小、锚在房间中央家具上，裁剪未必包含全部墙体）：
          墙体在哪边贴到裁剪图边缘，bbox 哪条边就贴到边缘（坐标取 0 或 1），严禁把可见的家具群当作空间边界

        只输出 JSON：{"bbox": {...}, "roomType": "...", "label": "..."}
        """;

    /**
     * 逐房间二次精修（户型图优化二期）：对初检的每个房间按其 bbox 外扩 25% 从原图
     * 裁小图（短边放大到约 768px），单独问 AI 输出该空间墙体内侧边界框 + 确认
     * roomType/label，映射回原图坐标后替换初检 bbox。
     *
     * <p>容错：精修调用失败、返回非法（bbox 解析失败、映射越界、面积与初检偏差超
     * 3 倍、或面积缩到初检 0.8 倍以下——一期实测残留为框偏小/锚家具，显著缩小即劣化）
     * 一律保留初检结果；roomType/label 仅在 bbox 采信时才随精修采用（AI 改了
     * 就采用，变化记日志）。逐房间串行调用（避免限流），房间数超
     * {@code rsdp.floor-plan.refine-max-rooms} 时按 bbox 面积从大到小截取。
     * 本方法只做精修，开关由调用方控制（管理端 refine-enabled / 官网 refine-public-enabled）。</p>
     *
     * @param imageBytes 户型原图字节
     * @param result     初检识别结果（就地精修 bbox/roomType/label）
     */
    public void refineFloorPlanRooms(byte[] imageBytes, FloorPlanDetectResult result) {
        if (result == null || result.getRooms() == null || result.getRooms().isEmpty()
            || imageBytes == null || imageBytes.length == 0 || mockEnabled) {
            return;
        }
        int maxRooms = refineMaxRooms != null && refineMaxRooms > 0 ? refineMaxRooms : 12;
        List<FloorPlanDetectResult.Room> candidates = result.getRooms().stream()
            .filter(r -> r.getX() != null && r.getY() != null && r.getW() != null && r.getH() != null
                && r.getW() > 0 && r.getH() > 0)
            .sorted(java.util.Comparator.comparingDouble(r -> -(r.getW() * r.getH())))
            .limit(maxRooms)
            .toList();
        if (candidates.size() < result.getRooms().size()) {
            log.info("户型图精修房间数超限，按 bbox 面积从大到小取前 {} 个（初检共 {} 个）",
                candidates.size(), result.getRooms().size());
        }
        int refined = 0;
        for (FloorPlanDetectResult.Room room : candidates) {
            try {
                if (refineOneRoom(imageBytes, room)) {
                    refined++;
                }
            } catch (Exception e) {
                log.warn("户型图空间精修异常，保留初检结果，label={}：{}", room.getLabel(), e.getMessage());
            }
        }
        log.info("户型图逐房间精修完成：候选 {} 间，采信 {} 间", candidates.size(), refined);
    }

    /**
     * 精修单个房间：外扩裁剪 → 小图独立识别 → 坐标映射回原图 → 合法性校验 → 采信。
     *
     * @return 精修结果被采信返回 true；任何一步失败返回 false（保留初检结果）
     */
    private boolean refineOneRoom(byte[] imageBytes, FloorPlanDetectResult.Room room) {
        ProductBoundingBox initial =
            new ProductBoundingBox(room.getX(), room.getY(), room.getW(), room.getH());
        if (!initial.isValid()) {
            return false;
        }
        ProductBoundingBox cropRegion = expandRoomBox(initial, REFINE_CROP_MARGIN_RATIO);
        byte[] crop = cropRoomToPng(imageBytes, cropRegion);
        if (crop == null) {
            return false;
        }

        String suspected = StringUtils.hasText(room.getLabel()) ? room.getLabel()
            : (room.getRoomType() != null ? room.getRoomType() : "空间");
        OpenAiChatRequest request = OpenAiChatRequest.builder()
            .model(model)
            .messages(List.of(
                OpenAiChatMessage.text("system", FLOOR_PLAN_REFINE_SYSTEM_PROMPT),
                OpenAiChatMessage.vision("user",
                    FLOOR_PLAN_REFINE_USER_PROMPT_TEMPLATE.formatted(suspected),
                    Base64.getEncoder().encodeToString(crop),
                    floorPlanMinPixels, floorPlanMaxPixels)
            ))
            .temperature(0.1)
            .maxTokens(1024)
            .responseFormat(OpenAiChatRequest.ResponseFormat.builder().type("json_object").build())
            .build();

        String json = executeChat(request, "户型图空间精修");
        ProductBoundingBox refinedSmall = parseBoundingBoxClamped(extractJsonField(json, "bbox"));
        if (refinedSmall == null) {
            log.warn("户型图空间精修未返回合法 bbox，保留初检结果，label={}，json={}", room.getLabel(), json);
            return false;
        }
        ProductBoundingBox mapped = mapToOriginalCoords(refinedSmall, cropRegion);
        if (mapped == null) {
            log.warn("户型图空间精修坐标映射越界，保留初检结果，label={}", room.getLabel());
            return false;
        }
        double areaRatio = (mapped.getWidth() * mapped.getHeight())
            / (initial.getWidth() * initial.getHeight());
        if (areaRatio < REFINE_MIN_AREA_RATIO || areaRatio > REFINE_AREA_TOLERANCE) {
            log.warn("户型图空间精修面积偏差超限（{} 倍，合法区间 [{}~{}]），保留初检结果，label={}",
                String.format("%.2f", areaRatio), REFINE_MIN_AREA_RATIO, REFINE_AREA_TOLERANCE, room.getLabel());
            return false;
        }

        room.setX(mapped.getX());
        room.setY(mapped.getY());
        room.setW(mapped.getWidth());
        room.setH(mapped.getHeight());

        // 类型确认：精修时看得更清，AI 改了 roomType/label 就采用（变化记日志）
        String refinedType = extractJsonTextField(json, "roomType");
        if (StringUtils.hasText(refinedType) && VALID_ROOM_TYPES.contains(refinedType.trim())
            && !refinedType.trim().equals(room.getRoomType())) {
            log.info("户型图精修修正 roomType：{} → {}（label={}）",
                room.getRoomType(), refinedType.trim(), room.getLabel());
            room.setRoomType(refinedType.trim());
        }
        String refinedLabel = extractJsonTextField(json, "label");
        if (StringUtils.hasText(refinedLabel) && !refinedLabel.trim().equals(room.getLabel())) {
            log.info("户型图精修修正 label：{} → {}", room.getLabel(), refinedLabel.trim());
            room.setLabel(refinedLabel.trim());
        }
        return true;
    }

    /** 从精修返回 JSON 提取对象字段（如 bbox）；解析失败返回 null。 */
    private Object extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<?, ?> map = objectMapper.readValue(json, Map.class);
            return map.get(field);
        } catch (Exception e) {
            log.warn("解析户型图精修结果失败，json={}", json, e);
            return null;
        }
    }

    /** 从精修返回 JSON 提取文本字段（如 roomType/label）；解析失败或缺失返回 null。 */
    private String extractJsonTextField(String json, String field) {
        Object value = extractJsonField(json, field);
        return value != null ? value.toString() : null;
    }

    /**
     * 按房间 bbox 自身宽高的指定比例四边外扩（钳制到 [0,1]）。
     * 与 {@link #expandRegion}（按全图归一化比例外扩）不同：精修外扩量跟随房间自身尺寸。
     */
    static ProductBoundingBox expandRoomBox(ProductBoundingBox box, double marginRatio) {
        double mx = box.getWidth() * marginRatio;
        double my = box.getHeight() * marginRatio;
        double x = clamp01(box.getX() - mx);
        double y = clamp01(box.getY() - my);
        double w = Math.min(box.getWidth() + 2 * mx, 1.0 - x);
        double h = Math.min(box.getHeight() + 2 * my, 1.0 - y);
        return new ProductBoundingBox(x, y, w, h);
    }

    /**
     * 按区域从原图裁剪小图并编码为 PNG（精修专用）：裁剪图短边不足
     * {@link #REFINE_UPSCALE_SHORT_EDGE} 时等比放大；图片无法解码返回 null（调用方保留初检）。
     */
    static byte[] cropRoomToPng(byte[] imageBytes, ProductBoundingBox region) {
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (source == null) {
                return null;
            }
            int sx = (int) Math.round(region.getX() * source.getWidth());
            int sy = (int) Math.round(region.getY() * source.getHeight());
            int sw = (int) Math.round(region.getWidth() * source.getWidth());
            int sh = (int) Math.round(region.getHeight() * source.getHeight());
            sx = Math.max(0, Math.min(sx, source.getWidth() - 1));
            sy = Math.max(0, Math.min(sy, source.getHeight() - 1));
            sw = Math.max(1, Math.min(sw, source.getWidth() - sx));
            sh = Math.max(1, Math.min(sh, source.getHeight() - sy));
            BufferedImage cropped = source.getSubimage(sx, sy, sw, sh);
            if (Math.min(cropped.getWidth(), cropped.getHeight()) < REFINE_UPSCALE_SHORT_EDGE) {
                cropped = upscaleToShortEdge(cropped, REFINE_UPSCALE_SHORT_EDGE);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(cropped, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("户型图空间精修裁剪失败，保留初检结果：{}", e.getMessage());
            return null;
        }
    }

    /** 等比放大图片使短边达到目标像素（双线性平滑）。 */
    static BufferedImage upscaleToShortEdge(BufferedImage source, int targetShortEdge) {
        double scale = targetShortEdge / (double) Math.min(source.getWidth(), source.getHeight());
        int targetWidth = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int targetHeight = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        } finally {
            g.dispose();
        }
        return scaled;
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
            .maxTokens(4096)
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
