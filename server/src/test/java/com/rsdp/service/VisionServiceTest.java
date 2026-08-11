package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rsdp.dto.AiLabels;
import com.rsdp.dto.ProductBoundingBox;
import com.rsdp.entity.CategoryDict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

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
}
