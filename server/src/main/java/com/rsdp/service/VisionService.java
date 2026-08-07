package com.rsdp.service;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
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
    private final SixDimSchemaService sixDimSchemaService;

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
        判断每页类型并输出页面中每个产品的相对位置框（bbox）。
        bbox 的准确度要求极高：必须紧贴产品主体边缘，宁可略大也不可切断产品，
        但不得包含说明文字、页眉页脚等无关内容。
        同时，每个产品图旁边通常配有品名、型号、尺寸、价格等说明文字，
        这些文字不属于产品图（不要框进 bbox），但必须完整提取到 nearbyText 中。
        只输出 JSON 数组，不要任何其他文字说明。
        """;

    private static final String PAGE_DETECTION_USER_PROMPT_TEMPLATE = """
        下面是 %d 张连续的 PDF 页面图片，请按顺序逐页分析。

        对每一页，判断其类型并输出产品中每个产品的位置信息：
        - pageType: product（产品页）/ cover（封面）/ toc（目录）/ separator（分隔页）/ blank（空白页）/ unknown（未知）
        - products: 当 pageType=product 时，列出该页中所有产品的位置框、预估品类码和产品旁的说明文字

        bbox 使用相对于页面宽高的比例坐标（0.0 ~ 1.0）：
        {"x": 左上角 x, "y": 左上角 y, "w": 宽度, "h": 高度}

        bbox 规则（必须严格遵守）：
        - 必须紧贴产品主体边缘，允许略微外扩，但绝不可切断产品的任何部分
        - 不得包含产品名称、价格、参数说明等文字，不得包含页眉页脚和页边距
        - 每个产品独立一个框：禁止把多个产品合并为一个框，也禁止把一个产品拆成多个框
        - 页面中有多个产品时必须全部列出，不得遗漏；一个都没有时 products 输出空数组
        - 产品图几乎占满整页时，给出接近整页的框是允许的
        - 坐标必须满足 0<=x、0<=y、x+w<=1、y+h<=1

        imageKind 规则（每个产品必须标注）：
        - standalone：单品图——白底或纯色/摄影棚背景的产品拍摄图，画面主体只有产品本身
        - scene：场景图/效果图——产品置于房间、展厅等真实或渲染环境中，画面含墙面、地面、
          窗帘、装饰品等环境元素。场景图整图视为一个 scene 对象，框住整张场景图即可，
          禁止把场景内的单个产品单独框出
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
                    pp.setBbox(parseBoundingBox(pm.get("bbox")));
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
