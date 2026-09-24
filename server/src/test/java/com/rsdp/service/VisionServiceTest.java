package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.CategoryShadowPrediction;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.CategoryDict;
import com.rsdp.entity.KnowledgeProductType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * {@link VisionService} 单元测试，使用 WireMock 模拟 DashScope API。
 */
class VisionServiceTest {

    private WireMockServer wireMockServer;
    private VisionService visionService;
    private DictService dictService;
    private SixDimSchemaService sixDimSchemaService;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        configureFor(wireMockServer.port());

        RestClient restClient = RestClient.builder()
            .baseUrl(wireMockServer.baseUrl())
            .defaultHeader("Authorization", "Bearer test-key")
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(new org.springframework.http.client.SimpleClientHttpRequestFactory())
            .build();

        dictService = mock(DictService.class);
        stubDictFor("style", List.of("中古风", "奶油风", "侘寂风"));
        stubDictFor("scene", List.of("客厅", "书房"));
        stubDictFor("material", List.of("实木", "布艺"));

        // 六维维度定义（P4 配置化后改为服务注入）：固定返回 A-F 标签定义
        sixDimSchemaService = mock(SixDimSchemaService.class);
        when(sixDimSchemaService.buildPromptDescription(any()))
            .thenReturn("本产品的六维标签定义如下（请严格按 A-F 输出，键名不变）：\n");
        when(sixDimSchemaService.getSchema(any())).thenReturn(schemaWithLabels());

        visionService = new VisionService(restClient, new ObjectMapper(), dictService, sixDimSchemaService);
    }

    private com.rsdp.dto.response.SixDimSchemaResponse schemaWithLabels() {
        java.util.Map<String, com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition> dims = new java.util.LinkedHashMap<>();
        dims.put("A", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("轮廓形态", ""));
        dims.put("B", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("靠背/背部特征", ""));
        dims.put("C", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("扶手特征", ""));
        dims.put("D", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("腿部/底座特征", ""));
        dims.put("E", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("表面材质", ""));
        dims.put("F", new com.rsdp.dto.response.SixDimSchemaResponse.DimDefinition("软包填充形态", ""));
        return new com.rsdp.dto.response.SixDimSchemaResponse("SF", "沙发", dims);
    }

    private void stubDictFor(String dictType, List<String> names) {
        List<CategoryDict> dicts = names.stream()
            .map(name -> {
                CategoryDict d = new CategoryDict();
                d.setDictType(dictType);
                d.setDictName(name);
                d.setDictCode(name);
                return d;
            })
            .toList();
        when(dictService.listByType(dictType)).thenReturn(dicts);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private String buildChatCompletionResponseBody(String contentJson) throws Exception {
        String quoted = new ObjectMapper().writeValueAsString(contentJson);
        return """
            {
              "choices": [
                {
                  "message": {
                    "role": "assistant",
                    "content": %s
                  }
                }
              ]
            }
            """.formatted(quoted);
    }

    @Test
    void recognizeImage_shouldReturnParsedLabels() throws Exception {
        String aiJson = """
            {
              "style": "中古风",
              "sixDimTags": {"A":"A字架形","B":"编织靠背","C":"无扶手","D":"细锥腿","E":"实木","F":"软包"},
              "colorPrimaryName": "焦糖棕",
              "colorPrimaryHsv": [30, 0.6, 0.5],
              "materialTags": ["实木", "布艺"],
              "sceneTags": ["客厅", "书房"],
              "confidence": "high"
            }
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());

        AiLabels labels = visionService.recognizeImage(imageStream);

        assertThat(labels.getStyle()).isEqualTo("中古风");
        assertThat(labels.getColorPrimaryName()).isEqualTo("焦糖棕");
        assertThat(labels.getConfidence()).isEqualTo("high");
        assertThat(labels.getSixDimTags()).containsEntry("A", "A字架形");
    }

    @Test
    void recognizeImage_shouldTolerateArrayValuedSixDimTag() throws Exception {
        // 实测案例：AI 偶发把六维 E 维返回为数组 ["实木","布艺"]，
        // Map<String,String> 反序列化会抛 MismatchedInputException 导致整个识别判失败
        String aiJson = """
            {
              "style": "侘寂",
              "sixDimTags": {
                "A": "一字型",
                "E": ["实木", "布艺"],
                "F": "饱满蓬松软包"
              },
              "confidence": "high",
              "ocr": {"productName": "扶摇沙发", "dimensionText": "2380*840*910/2600*840*910"}
            }
            """;
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        AiLabels labels = visionService.recognizeImage(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(labels.getStyle()).isEqualTo("侘寂");
        assertThat(labels.getSixDimTags())
            .containsEntry("A", "一字型")
            .containsEntry("E", "实木/布艺")
            .containsEntry("F", "饱满蓬松软包");
        assertThat(labels.getOcr().getProductName()).isEqualTo("扶摇沙发");
    }

    @Test
    void classifyCategory_shouldReturnDictCode() throws Exception {
        stubCategoryDict("FS", "SF", "FC");
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"categoryCode\":\"FC\"}"))));

        String code = visionService.classifyCategory(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(code).isEqualTo("FC");
    }

    @Test
    void classifyCategory_shouldReturnNull_whenCodeNotInDict() throws Exception {
        stubCategoryDict("FS", "SF", "FC");
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"categoryCode\":\"XX\"}"))));

        assertThat(visionService.classifyCategory(new ByteArrayInputStream("fake-image".getBytes()))).isNull();
    }

    @Test
    void classifyCategory_shouldReturnNull_whenAiCannotDecide() throws Exception {
        stubCategoryDict("FS");
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"categoryCode\":null}"))));

        assertThat(visionService.classifyCategory(new ByteArrayInputStream("fake-image".getBytes()))).isNull();
    }

    @Test
    void classifyCategoryShadow_shouldUseFullDictionaryAndProductTypes() throws Exception {
        CategoryDict fs = category("FS", "座椅");
        CategoryDict dk = category("DK", "书桌/写字台");
        when(dictService.listAllByType("category")).thenReturn(List.of(fs, dk));
        KnowledgeProductType writingDesk = productType("WRITING_DESK", "写字台", "DK");
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("""
                    {"categoryCode":"DK","productType":"WRITING_DESK","confidence":"high","reason":"可见写字台面"}
                    """))));

        CategoryShadowPrediction prediction = visionService.classifyCategoryShadow(
            new ByteArrayInputStream("fake-image".getBytes()), Set.of("FS", "DK"), List.of(writingDesk));

        assertThat(prediction.categoryCode()).isEqualTo("DK");
        assertThat(prediction.productType()).isEqualTo("WRITING_DESK");
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content[1].text", containing("DK(书桌/写字台)")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[1].text", containing("WRITING_DESK"))));
    }

    @Test
    void classifyCategoryShadow_shouldClearCrossCategoryProductType() throws Exception {
        when(dictService.listAllByType("category")).thenReturn(List.of(category("DK", "书桌/写字台")));
        KnowledgeProductType wrongType = productType("DINING_CHAIR", "餐椅", "FS");
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"categoryCode\":\"DK\",\"productType\":\"DINING_CHAIR\",\"confidence\":\"mid\"}"))));

        CategoryShadowPrediction prediction = visionService.classifyCategoryShadow(
            new ByteArrayInputStream("fake-image".getBytes()), Set.of("DK", "FS"), List.of(wrongType));

        assertThat(prediction.categoryCode()).isEqualTo("DK");
        assertThat(prediction.productType()).isNull();
    }

    private CategoryDict category(String code, String name) {
        CategoryDict dict = new CategoryDict();
        dict.setDictType("category");
        dict.setDictCode(code);
        dict.setDictName(name);
        dict.setStatus("active");
        return dict;
    }

    private KnowledgeProductType productType(String code, String name, String category) {
        KnowledgeProductType type = new KnowledgeProductType();
        type.setTypeCode(code);
        type.setTypeName(name);
        type.setBusinessCategoryCode(category);
        type.setAliases("[]");
        return type;
    }

    private void stubCategoryDict(String... codes) {
        List<CategoryDict> dicts = java.util.Arrays.stream(codes)
            .map(code -> {
                CategoryDict d = new CategoryDict();
                d.setDictType("category");
                d.setDictCode(code);
                d.setDictName(code);
                return d;
            })
            .toList();
        when(dictService.listByType("category")).thenReturn(dicts);
    }

    @Test
    void recognizeImage_shouldThrowWhenApiReturnsEmpty() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"choices\": []}")));

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());

        assertThatThrownBy(() -> visionService.recognizeImage(imageStream))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("API 返回为空");
    }

    @Test
    void recognizeImage_shouldSendJsonObjectFormatAndMaxTokens() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"style\":\"中古风\"}"))));

        visionService.recognizeImage(new ByteArrayInputStream("fake-image".getBytes()));

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
            .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096"))));
    }

    @Test
    void recognizeImage_dualImage_shouldSendTwoImagesWithRoleNote() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"style\":\"中古风\",\"ocr\":{\"productName\":\"昌迪加尔餐椅\"}}"))));

        AiLabels labels = visionService.recognizeImage(
            new ByteArrayInputStream("cropped-image".getBytes()),
            "original-image".getBytes(),
            "FS");

        assertThat(labels.getOcr().getProductName()).isEqualTo("昌迪加尔餐椅");
        // 双图模式：content 前两段为图片（裁剪图 + 原图），末段文本含分工说明
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].type", equalTo("image_url")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[1].type", equalTo("image_url")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[2].type", equalTo("text")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[2].text", containing("原始上传图"))));
    }

    @Test
    void recognizeImage_dualImageWithoutOriginal_shouldFallBackToSingleImage() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"style\":\"中古风\"}"))));

        visionService.recognizeImage(
            new ByteArrayInputStream("fake-image".getBytes()), null, "FS");

        // 无原图时退化为单图：content 仅 1 图 1 文
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].type", equalTo("image_url")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[1].type", equalTo("text"))));
    }

    @Test
    void recognizeImage_shouldThrowParseErrorWhenAiReturnsInvalidJson() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("这不是 JSON"))));

        InputStream imageStream = new ByteArrayInputStream("fake-image".getBytes());

        // 解析失败应报「解析 AI 识别结果失败」，而不是误报为「读取图片流失败」
        assertThatThrownBy(() -> visionService.recognizeImage(imageStream))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("解析 AI 识别结果失败");
    }

    @Test
    void detectProductSubject_shouldParseBbox() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"bbox\": {\"x\": 0.1, \"y\": 0.05, \"w\": 0.8, \"h\": 0.9}}"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNotNull();
        assertThat(bbox.getX()).isEqualTo(0.1);
        assertThat(bbox.getY()).isEqualTo(0.05);
        assertThat(bbox.getWidth()).isEqualTo(0.8);
        assertThat(bbox.getHeight()).isEqualTo(0.9);
    }

    @Test
    void detectProductSubject_shouldReturnNullWhenNoSubject() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"bbox\": null}"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNull();
    }

    @Test
    void detectProductSubject_shouldReturnNullOnInvalidJson() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("这不是 JSON"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNull();
    }

    @Test
    void detectProductSubject_shouldReturnNullOnInvalidBboxCoords() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"bbox\": {\"x\": 0.9, \"y\": 0.9, \"w\": 0.5, \"h\": 0.5}}"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNull();
    }

    @Test
    void detectProductSubject_shouldParseXyxyPermilleBbox() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"bbox\": [100, 50, 900, 950]}"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNotNull();
        assertThat(bbox.getX()).isEqualTo(0.1);
        assertThat(bbox.getY()).isEqualTo(0.05);
        assertThat(bbox.getWidth()).isEqualTo(0.8);
        assertThat(bbox.getHeight()).isEqualTo(0.9);
    }

    @Test
    void detectProductSubject_shouldReturnNullOnInvalidXyxy() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"bbox\": [900, 900, 100, 100]}"))));

        var bbox = visionService.detectProductSubject(new ByteArrayInputStream("fake-image".getBytes()));

        assertThat(bbox).isNull();
    }

    @Test
    void isProductComplete_shouldReturnTrue() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"complete\": true}"))));

        assertThat(visionService.isProductComplete(new ByteArrayInputStream("fake-image".getBytes()))).isTrue();
    }

    @Test
    void isProductComplete_shouldReturnFalse() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"complete\": false}"))));

        assertThat(visionService.isProductComplete(new ByteArrayInputStream("fake-image".getBytes()))).isFalse();
    }

    @Test
    void isProductComplete_shouldReturnTrueOnInvalidResponse() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("这不是 JSON"))));

        // 校验失败时默认视为完整，不误杀裁剪结果
        assertThat(visionService.isProductComplete(new ByteArrayInputStream("fake-image".getBytes()))).isTrue();
    }

    @Test
    void detectPageRegions_shouldClosePageStreams() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("[{\"pageType\":\"blank\",\"products\":[]}]"))));

        class CloseTrackingStream extends ByteArrayInputStream {
            boolean closed;
            CloseTrackingStream(byte[] buf) {
                super(buf);
            }
            @Override
            public void close() {
                closed = true;
            }
        }
        CloseTrackingStream stream = new CloseTrackingStream("fake-page".getBytes());

        visionService.detectPageRegions(List.of(stream), null);

        assertThat(stream.closed).isTrue();
    }

    @Test
    void detectPageRegions_shouldParseCompleteJson() throws Exception {
        String aiJson = """
            [
              {"pageType": "product", "products": [{"bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5}, "estimatedCategory": "SF", "imageKind": "scene"}]},
              {"pageType": "cover", "products": []}
            ]
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        List<InputStream> images = List.of(
            new ByteArrayInputStream("fake-page-1".getBytes()),
            new ByteArrayInputStream("fake-page-2".getBytes())
        );

        var regions = visionService.detectPageRegions(images, null);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).getPageType()).isEqualTo("product");
        assertThat(regions.get(0).getProducts()).hasSize(1);
        assertThat(regions.get(0).getProducts().get(0).getEstimatedCategory()).isEqualTo("SF");
        assertThat(regions.get(0).getProducts().get(0).getImageKind()).isEqualTo("scene");
        assertThat(regions.get(1).getPageType()).isEqualTo("cover");
    }

    @Test
    void detectPageRegions_shouldClampOutOfBoundsBBox() throws Exception {
        // 模型偶发给出超出页面的框（x=0.48、w=0.87 → x+w>1）：
        // 应收敛到页内而不是整框丢弃（该失败模式曾导致整批产品丢失）
        String aiJson = """
            [
              {"pageType": "product", "products": [{"bbox": {"x": 0.48, "y": 0.18, "w": 0.87, "h": 0.26}, "estimatedCategory": "SF"}]}
            ]
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        List<InputStream> images = List.of(new ByteArrayInputStream("fake-page-1".getBytes()));

        var regions = visionService.detectPageRegions(images, null);

        assertThat(regions.get(0).getProducts()).hasSize(1);
        ProductBoundingBox bbox = regions.get(0).getProducts().get(0).getBbox();
        assertThat(bbox).isNotNull();
        assertThat(bbox.isValid()).isTrue();
        assertThat(bbox.getX() + bbox.getWidth()).isLessThanOrEqualTo(1.0);
        assertThat(bbox.getWidth()).isCloseTo(0.52, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void detectPageRegions_shouldRecoverFromTruncatedJson() throws Exception {
        String aiJson = """
            [
              {"pageType": "product", "products": [{"bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5}, "estimatedCategory": "SF"}]},
              {"pageType": "product", "products": [{"bbox": {"x": 0.6, "y": 0.2, "w": 0.3, "h": 0.4
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        List<InputStream> images = List.of(
            new ByteArrayInputStream("fake-page-1".getBytes()),
            new ByteArrayInputStream("fake-page-2".getBytes())
        );

        var regions = visionService.detectPageRegions(images, null);

        assertThat(regions).hasSize(2);
        assertThat(regions.get(0).getPageType()).isEqualTo("product");
        assertThat(regions.get(1).getPageType()).isEqualTo("unknown");
    }

    @Test
    void detectPageRegions_shouldParseNearbyText() throws Exception {
        String aiJson = """
            [
              {"pageType": "product", "products": [{
                "bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5},
                "estimatedCategory": "SF",
                "nearbyText": {
                  "productName": "兰卡沙发",
                  "modelNumber": "LK-2450",
                  "dimensionText": "2450×900×850mm",
                  "priceText": "¥12800",
                  "rawText": "兰卡沙发 LK-2450 2450×900×850mm ¥12800"
                }
              }]}
            ]
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        List<InputStream> images = List.of(new ByteArrayInputStream("fake-page-1".getBytes()));

        var regions = visionService.detectPageRegions(images, null);

        assertThat(regions).hasSize(1);
        var product = regions.get(0).getProducts().get(0);
        assertThat(product.getNearbyText()).isNotNull();
        assertThat(product.getNearbyText().getProductName()).isEqualTo("兰卡沙发");
        assertThat(product.getNearbyText().getModelNumber()).isEqualTo("LK-2450");
        assertThat(product.getNearbyText().getDimensionText()).isEqualTo("2450×900×850mm");
        assertThat(product.getNearbyText().getPriceText()).isEqualTo("¥12800");
    }

    @Test
    void detectPageRegions_shouldTolerateMissingNearbyText() throws Exception {
        String aiJson = """
            [
              {"pageType": "product", "products": [{"bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5}, "estimatedCategory": "SF", "nearbyText": null}]}
            ]
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        List<InputStream> images = List.of(new ByteArrayInputStream("fake-page-1".getBytes()));

        var regions = visionService.detectPageRegions(images, null);

        assertThat(regions).hasSize(1);
        assertThat(regions.get(0).getProducts().get(0).getNearbyText()).isNull();
        assertThat(regions.get(0).getProducts().get(0).getEstimatedCategory()).isEqualTo("SF");
    }

    @Test
    void recognizeImage_shouldInjectSixDimEnumsIntoPrompt() throws Exception {
        // P1 枚举化：按品类注入六维枚举（中文名+锚点），约束 AI 从枚举中选择
        CategoryDict arm = new CategoryDict();
        arm.setDictType("six_dim_C");
        arm.setDictCode("SF-宽厚扶手");
        arm.setDictName("宽厚扶手");
        arm.setParentCode("SF");
        arm.setSortOrder(3);
        arm.setRemark("扶手又宽又厚，顶面可置物/坐人");
        CategoryDict other = new CategoryDict();
        other.setDictType("six_dim_C");
        other.setDictCode("SF-异形/其他");
        other.setDictName("异形/其他");
        other.setParentCode("SF");
        other.setSortOrder(99);
        other.setRemark("不属于以上形态");
        CategoryDict tbEntry = new CategoryDict();
        tbEntry.setDictType("six_dim_C");
        tbEntry.setDictCode("TB-直边");
        tbEntry.setDictName("直边");
        tbEntry.setParentCode("TB");
        tbEntry.setSortOrder(1);
        when(dictService.listByType("six_dim_C")).thenReturn(List.of(arm, other, tbEntry));

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"style\":\"中古风\"}"))));

        visionService.recognizeImage(new ByteArrayInputStream("fake-image".getBytes()), "SF");

        // 注入本品类枚举名+锚点与选择约束；不注入其他品类条目
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("必须从下列对应枚举中精确选择一项"))
            .withRequestBody(containing("宽厚扶手（扶手又宽又厚，顶面可置物/坐人）"))
            .withRequestBody(containing("异形/其他"))
            .withRequestBody(notMatching(".*TB-直边.*")));
    }

    @Test
    void recognizeImage_shouldSkipSixDimEnumInjectionWhenNoCategoryDict() throws Exception {
        // 品类无六维字典（或未指定品类）时不注入枚举约束，行为与枚举化前一致
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"style\":\"中古风\"}"))));

        visionService.recognizeImage(new ByteArrayInputStream("fake-image".getBytes()));

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(notMatching(".*必须从下列对应枚举中精确选择一项.*")));
    }

    @Test
    void detectFloorPlanRooms_shouldParseRoomsAndScaleText() throws Exception {
        String aiJson = """
            {
              "rooms": [
                {"roomType": "living_room", "bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.3}, "dimensionText": "4200×3800", "label": "客厅"},
                {"roomType": "bedroom", "dimensionText": null, "label": "主卧"}
              ],
              "scaleText": "1:50"
            }
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var result = visionService.detectFloorPlanRooms("fake-plan".getBytes(), "三室两厅");

        assertThat(result.getScaleText()).isEqualTo("1:50");
        assertThat(result.getRooms()).hasSize(2);
        var living = result.getRooms().get(0);
        assertThat(living.getRoomType()).isEqualTo("living_room");
        assertThat(living.getLabel()).isEqualTo("客厅");
        assertThat(living.getDimensionText()).isEqualTo("4200×3800");
        assertThat(living.getX()).isEqualTo(0.1);
        assertThat(living.getW()).isEqualTo(0.4);
        // bbox 缺失的空间坐标为 null，不整行丢弃
        var bedroom = result.getRooms().get(1);
        assertThat(bedroom.getRoomType()).isEqualTo("bedroom");
        assertThat(bedroom.getX()).isNull();

        // hint 应透传到 user prompt
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("三室两厅")));
    }

    @Test
    void detectFloorPlanRooms_shouldClampOutOfBoundsBBox() throws Exception {
        // bbox 越界收敛到图内，不整框丢弃（沿用 PDF 链路教训）
        String aiJson = """
            {
              "rooms": [
                {"roomType": "living_room", "bbox": {"x": 0.6, "y": 0.5, "w": 0.9, "h": 0.8}, "label": "客厅"}
              ],
              "scaleText": null
            }
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var result = visionService.detectFloorPlanRooms("fake-plan".getBytes(), null);

        assertThat(result.getRooms()).hasSize(1);
        var room = result.getRooms().get(0);
        assertThat(room.getX() + room.getW()).isLessThanOrEqualTo(1.0);
        assertThat(room.getY() + room.getH()).isLessThanOrEqualTo(1.0);
        assertThat(room.getW()).isCloseTo(0.4, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void detectFloorPlanRooms_shouldSendJsonObjectFormatAndMaxTokens() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"rooms\": [], \"scaleText\": null}"))));

        visionService.detectFloorPlanRooms("fake-plan".getBytes(), null);

        // 工程保险：response_format=json_object + maxTokens 8192（防截断）
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
            .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("8192"))));
    }

    @Test
    void detectFloorPlanRooms_shouldRecoverFromTruncatedJson() throws Exception {
        // maxTokens 截断：rooms 数组最后一个对象不完整，截尾修复后保留已完整房间
        String aiJson = """
            {"rooms": [
              {"roomType": "living_room", "bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.3}, "dimensionText": "4200×3800", "label": "客厅"},
              {"roomType": "bedroom", "bbox": {"x": 0.6, "y": 0.2, "w": 0.3, "h
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var result = visionService.detectFloorPlanRooms("fake-plan".getBytes(), null);

        assertThat(result.getRooms()).hasSize(1);
        var room = result.getRooms().get(0);
        assertThat(room.getRoomType()).isEqualTo("living_room");
        assertThat(room.getLabel()).isEqualTo("客厅");
        assertThat(room.getDimensionText()).isEqualTo("4200×3800");
        assertThat(room.getX()).isEqualTo(0.1);
    }

    @Test
    void detectFloorPlanRooms_invalidJson_shouldThrowExternalServiceException() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("not-a-json"))));

        assertThatThrownBy(() -> visionService.detectFloorPlanRooms("fake-plan".getBytes(), null))
            .isInstanceOf(com.rsdp.exception.ExternalServiceException.class)
            .hasMessageContaining("解析 AI 识别结果失败");
    }

    // ========== 两阶段识别：户型本体区域检测 + 裁剪 + 坐标映射 ==========

    @Test
    void isPlausibleFloorPlanRegion_shouldAcceptNormalRegion() {
        // 户型本体约占原图 50%：采信
        assertThat(VisionService.isPlausibleFloorPlanRegion(
            new ProductBoundingBox(0.2, 0.1, 0.6, 0.8))).isTrue();
    }

    @Test
    void isPlausibleFloorPlanRegion_shouldRejectTooSmallOrTooLarge() {
        // 面积 <25%（可能误框了标题栏/图例）：不采信
        assertThat(VisionService.isPlausibleFloorPlanRegion(
            new ProductBoundingBox(0.1, 0.8, 0.4, 0.15))).isFalse();
        // 面积 >95%（几乎整图，等于没定位）：不采信
        assertThat(VisionService.isPlausibleFloorPlanRegion(
            new ProductBoundingBox(0.01, 0.01, 0.98, 0.98))).isFalse();
        // null / 非法 bbox：不采信
        assertThat(VisionService.isPlausibleFloorPlanRegion(null)).isFalse();
        assertThat(VisionService.isPlausibleFloorPlanRegion(
            new ProductBoundingBox(0.9, 0.9, 0.5, 0.5))).isFalse();
    }

    @Test
    void mapToOriginalCoords_shouldMapCroppedBoxBackToOriginal() {
        // 裁剪区域 x=0.2,y=0.1,w=0.6,h=0.8；裁剪图内框 x=0.5,y=0.25,w=0.25,h=0.25
        // 映射回原图：x=0.2+0.5*0.6=0.5, y=0.1+0.25*0.8=0.3, w=0.25*0.6=0.15, h=0.25*0.8=0.2
        ProductBoundingBox mapped = VisionService.mapToOriginalCoords(
            new ProductBoundingBox(0.5, 0.25, 0.25, 0.25),
            new ProductBoundingBox(0.2, 0.1, 0.6, 0.8));

        assertThat(mapped).isNotNull();
        assertThat(mapped.getX()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(mapped.getY()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(mapped.getWidth()).isCloseTo(0.15, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(mapped.getHeight()).isCloseTo(0.2, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void cropRegionToPng_shouldCropByNormalizedRegion() throws Exception {
        byte[] png = newPngBytes(200, 100);
        byte[] cropped = VisionService.cropRegionToPng(png, new ProductBoundingBox(0.25, 0.2, 0.5, 0.6));

        assertThat(cropped).isNotNull();
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(cropped));
        // 裁剪得 100×60，低于最小宽度 2000 触发等比放大 → 2000×1200
        assertThat(image.getWidth()).isEqualTo(2000);
        assertThat(image.getHeight()).isEqualTo(1200);
    }

    @Test
    void cropRegionToPng_shouldReturnNullOnUndecodableImage() {
        assertThat(VisionService.cropRegionToPng("fake-plan".getBytes(),
            new ProductBoundingBox(0.2, 0.1, 0.6, 0.8))).isNull();
    }

    @Test
    void overlayCoordinateGrid_shouldKeepSizeAndNotThrow() throws Exception {
        var source = javax.imageio.ImageIO.read(new ByteArrayInputStream(newPngBytes(300, 200)));
        var overlaid = VisionService.overlayCoordinateGrid(source);
        assertThat(overlaid.getWidth()).isEqualTo(300);
        assertThat(overlaid.getHeight()).isEqualTo(200);
    }

    @Test
    void detectFloorPlanRegion_shouldParseBbox() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"bbox\": {\"x\": 0.23, \"y\": 0.05, \"w\": 0.55, \"h\": 0.83}}"))));

        var region = visionService.detectFloorPlanRegion("fake-plan".getBytes());

        assertThat(region).isNotNull();
        assertThat(region.getX()).isEqualTo(0.23);
        assertThat(region.getWidth()).isEqualTo(0.55);
    }

    @Test
    void detectFloorPlanRegion_shouldReturnNullOnInvalidJson() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("这不是 JSON"))));

        assertThat(visionService.detectFloorPlanRegion("fake-plan".getBytes())).isNull();
    }

    @Test
    void detectFloorPlanRooms_twoStage_shouldCropAndMapBackToOriginalCoords() throws Exception {
        // 第一阶段（区域检测）：提示词含"户型图本体"，返回合法区域
        stubFor(post(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("户型图本体"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"bbox\": {\"x\": 0.2, \"y\": 0.1, \"w\": 0.6, \"h\": 0.8}}"))));
        // 第二阶段（空间识别，裁剪图上）：返回裁剪图内坐标
        stubFor(post(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("识别其中的各个功能空间"))
            .atPriority(5)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("""
                    {"rooms": [
                      {"roomType": "living_room", "bbox": {"x": 0.5, "y": 0.25, "w": 0.25, "h": 0.25}, "dimensionText": null, "label": "客厅"}
                    ], "scaleText": null}
                    """))));

        var result = visionService.detectFloorPlanRooms(newPngBytes(200, 100), null);

        assertThat(result.getRooms()).hasSize(1);
        var room = result.getRooms().get(0);
        // 裁剪前先外扩 4%：region (0.2,0.1,0.6,0.8) → (0.16,0.06,0.68,0.88)
        // 映射回原图：x=0.16+0.5*0.68=0.50, y=0.06+0.25*0.88=0.28, w=0.25*0.68=0.17, h=0.25*0.88=0.22
        assertThat(room.getX()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getY()).isCloseTo(0.28, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getW()).isCloseTo(0.17, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getH()).isCloseTo(0.22, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void expandRegion_shouldExpandByMarginOnAllSides() {
        ProductBoundingBox expanded = VisionService.expandRegion(
            new ProductBoundingBox(0.2, 0.1, 0.6, 0.8), 0.04);

        assertThat(expanded.getX()).isCloseTo(0.16, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(expanded.getY()).isCloseTo(0.06, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(expanded.getWidth()).isCloseTo(0.68, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(expanded.getHeight()).isCloseTo(0.88, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void expandRegion_shouldClampToImageBounds() {
        // 贴边区域外扩后不得越界：x/y 钳到 0，右/下边钳到 1
        ProductBoundingBox expanded = VisionService.expandRegion(
            new ProductBoundingBox(0.01, 0.02, 0.98, 0.97), 0.04);

        assertThat(expanded.getX()).isEqualTo(0.0);
        assertThat(expanded.getY()).isEqualTo(0.0);
        assertThat(expanded.getWidth()).isEqualTo(1.0);
        assertThat(expanded.getHeight()).isEqualTo(1.0);
    }

    @Test
    void detectFloorPlanRegion_shouldSendPixelBudgetInImagePart() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "floorPlanMinPixels", 200704);
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "floorPlanMaxPixels", 4194304);
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"bbox\": {\"x\": 0.23, \"y\": 0.05, \"w\": 0.55, \"h\": 0.83}}"))));

        visionService.detectFloorPlanRegion("fake-plan".getBytes());

        // 像素预算必须放在 content 的 image_url 部分内（DashScope qwen-vl 扩展参数，顶层无效）
        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].min_pixels", equalTo("200704")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].max_pixels", equalTo("4194304"))));
    }

    @Test
    void detectFloorPlanRooms_shouldSendPixelBudgetInImagePart() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "floorPlanMinPixels", 200704);
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "floorPlanMaxPixels", 4194304);
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"rooms\": [], \"scaleText\": null}"))));

        visionService.detectFloorPlanRooms("fake-plan".getBytes(), null);

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].min_pixels", equalTo("200704")))
            .withRequestBody(matchingJsonPath("$.messages[1].content[0].max_pixels", equalTo("4194304"))));
    }

    @Test
    void detectFloorPlanRooms_shouldFallbackToFullImageWhenRegionImplausible() throws Exception {
        // 区域检测返回过小区域（面积 <25%）：不采信，直接整图识别，bbox 不做映射
        stubFor(post(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("户型图本体"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(
                    "{\"bbox\": {\"x\": 0.1, \"y\": 0.8, \"w\": 0.4, \"h\": 0.15}}"))));
        stubFor(post(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("识别其中的各个功能空间"))
            .atPriority(5)
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("""
                    {"rooms": [
                      {"roomType": "living_room", "bbox": {"x": 0.3, "y": 0.55, "w": 0.38, "h": 0.28}, "dimensionText": null, "label": "客厅"}
                    ], "scaleText": null}
                    """))));

        var result = visionService.detectFloorPlanRooms(newPngBytes(200, 100), null);

        var room = result.getRooms().get(0);
        // 回退整图：坐标原样保留，不做区域映射
        assertThat(room.getX()).isCloseTo(0.3, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(room.getY()).isCloseTo(0.55, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(room.getW()).isCloseTo(0.38, org.assertj.core.data.Offset.offset(1e-6));
    }

    /** 生成纯白色 PNG 测试图字节。 */
    private static byte[] newPngBytes(int width, int height) throws Exception {
        var image = new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
        var out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    // ========== 户型图优化二期：逐房间二次精修 ==========

    private void stubRefineResponse(String contentJson) throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("裁剪出的一个空间"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(contentJson))));
    }

    private static com.rsdp.dto.FloorPlanDetectResult detectResultWith(
        com.rsdp.dto.FloorPlanDetectResult.Room... rooms) {
        var result = new com.rsdp.dto.FloorPlanDetectResult();
        result.setRooms(java.util.Arrays.asList(rooms));
        return result;
    }

    @Test
    void refineFloorPlanRooms_shouldReplaceBboxWithMappedRefinedBox() throws Exception {
        // 初检 bbox (0.4,0.2,0.2,0.2)；外扩 25%（自身尺寸）→ 裁剪区 (0.35,0.15,0.3,0.3)
        // 精修返回小图内 bbox (0.1,0.1,0.7,0.7) → 映射回原图 (0.38,0.18,0.21,0.21)
        // （面积比 1.10，在 [0.8, 3] 合法区间内采信）
        stubRefineResponse("""
            {"bbox": {"x": 0.1, "y": 0.1, "w": 0.7, "h": 0.7}, "roomType": "bedroom", "label": "主卧"}
            """);
        var room = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bedroom", "主卧", null, 0.4, 0.2, 0.2, 0.2);
        var result = detectResultWith(room);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        assertThat(room.getX()).isCloseTo(0.38, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getY()).isCloseTo(0.18, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getW()).isCloseTo(0.21, org.assertj.core.data.Offset.offset(1e-3));
        assertThat(room.getH()).isCloseTo(0.21, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void refineFloorPlanRooms_shouldKeepInitialWhenAreaShrinksTooMuch() throws Exception {
        // 精修返回极小框：映射后面积 < 初检 1/3，判定失控保留初检
        stubRefineResponse("""
            {"bbox": {"x": 0.4, "y": 0.4, "w": 0.1, "h": 0.1}, "roomType": "bedroom", "label": "主卧"}
            """);
        var room = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bedroom", "主卧", null, 0.4, 0.4, 0.1, 0.1);
        var result = detectResultWith(room);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        assertThat(room.getX()).isEqualTo(0.4);
        assertThat(room.getY()).isEqualTo(0.4);
        assertThat(room.getW()).isEqualTo(0.1);
        assertThat(room.getH()).isEqualTo(0.1);
    }

    @Test
    void refineFloorPlanRooms_shouldKeepInitialWhenResponseInvalid() throws Exception {
        stubRefineResponse("这不是 JSON");
        var room = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bedroom", "主卧", null, 0.4, 0.2, 0.2, 0.2);
        var result = detectResultWith(room);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        assertThat(room.getX()).isEqualTo(0.4);
        assertThat(room.getW()).isEqualTo(0.2);
    }

    @Test
    void refineFloorPlanRooms_shouldAdoptChangedRoomTypeAndLabel() throws Exception {
        // 精修看得更清：AI 把 bedroom/次卧 修正为 study/书房，采信（bbox 合法时）
        stubRefineResponse("""
            {"bbox": {"x": 0.1, "y": 0.1, "w": 0.7, "h": 0.7}, "roomType": "study", "label": "书房"}
            """);
        var room = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bedroom", "次卧", null, 0.4, 0.2, 0.2, 0.2);
        var result = detectResultWith(room);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        assertThat(room.getRoomType()).isEqualTo("study");
        assertThat(room.getLabel()).isEqualTo("书房");
    }

    @Test
    void refineFloorPlanRooms_shouldIgnoreEnumOutOfRangeRoomType() throws Exception {
        // AI 编造枚举外 roomType：不采信，bbox 精修仍生效
        stubRefineResponse("""
            {"bbox": {"x": 0.1, "y": 0.1, "w": 0.7, "h": 0.7}, "roomType": "garage", "label": "车库"}
            """);
        var room = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bedroom", "主卧", null, 0.4, 0.2, 0.2, 0.2);
        var result = detectResultWith(room);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        assertThat(room.getRoomType()).isEqualTo("bedroom");
        assertThat(room.getLabel()).isEqualTo("车库");
        assertThat(room.getX()).isCloseTo(0.38, org.assertj.core.data.Offset.offset(1e-3));
    }

    @Test
    void refineFloorPlanRooms_shouldLimitRoomsByMaxRoomsConfig() throws Exception {
        // refineMaxRooms=1：仅面积最大的房间发起精修调用
        org.springframework.test.util.ReflectionTestUtils.setField(visionService, "refineMaxRooms", 1);
        stubRefineResponse("""
            {"bbox": {"x": 0.2, "y": 0.1, "w": 0.5, "h": 0.6}, "roomType": "bedroom", "label": "主卧"}
            """);
        var big = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "living_room", "客厅", null, 0.1, 0.1, 0.4, 0.4);
        var small = new com.rsdp.dto.FloorPlanDetectResult.Room(
            "bathroom", "卫生间", null, 0.6, 0.6, 0.1, 0.1);
        var result = detectResultWith(big, small);

        visionService.refineFloorPlanRooms(newPngBytes(200, 100), result);

        // 仅 1 次精修请求（大房间），小房间保持初检
        verify(1, postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(containing("裁剪出的一个空间")));
        assertThat(small.getX()).isEqualTo(0.6);
        assertThat(small.getW()).isEqualTo(0.1);
    }

    @Test
    void expandRoomBox_shouldExpandByOwnSizeAndClamp() {
        // 外扩量跟随房间自身尺寸：mx=0.5*0.25=0.125，my=0.6*0.25=0.15；x/y 钳到 0
        ProductBoundingBox expanded = VisionService.expandRoomBox(
            new ProductBoundingBox(0.01, 0.02, 0.5, 0.6), 0.25);

        assertThat(expanded.getX()).isEqualTo(0.0);
        assertThat(expanded.getY()).isEqualTo(0.0);
        assertThat(expanded.getWidth()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(expanded.getHeight()).isCloseTo(0.9, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void cropRoomToPng_shouldUpscaleShortEdgeTo768() throws Exception {
        // 裁剪 100×60，短边 60 < 768 → 等比放大到 1280×768
        byte[] crop = VisionService.cropRoomToPng(newPngBytes(200, 100),
            new ProductBoundingBox(0.25, 0.2, 0.5, 0.6));

        assertThat(crop).isNotNull();
        var image = javax.imageio.ImageIO.read(new ByteArrayInputStream(crop));
        assertThat(image.getWidth()).isEqualTo(1280);
        assertThat(image.getHeight()).isEqualTo(768);
    }
}
