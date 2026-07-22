package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rsdp.dto.AiLabels;
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

        visionService = new VisionService(restClient, new ObjectMapper(), dictService);
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
              {"pageType": "product", "products": [{"bbox": {"x": 0.1, "y": 0.2, "w": 0.4, "h": 0.5}, "estimatedCategory": "SF"}]},
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
        assertThat(regions.get(1).getPageType()).isEqualTo("cover");
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
    void detectSceneProducts_shouldParseProductsAndSendJsonObjectFormat() throws Exception {
        String aiJson = """
            {
              "products": [
                {"bbox": {"x": 0.05, "y": 0.35, "width": 0.5, "height": 0.55}, "estimatedCategory": "SF", "label": "三人位沙发"},
                {"bbox": {"x": 0.6, "y": 0.4, "width": 0.3, "height": 0.4}, "estimatedCategory": "TB", "label": "茶几"}
              ]
            }
            """;

        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var products = visionService.detectSceneProducts(new ByteArrayInputStream("fake-scene".getBytes()), 12);

        assertThat(products).hasSize(2);
        assertThat(products.get(0).getEstimatedCategory()).isEqualTo("SF");
        assertThat(products.get(0).getBbox().getWidth()).isEqualTo(0.5);
        assertThat(products.get(0).getLabel()).isEqualTo("三人位沙发");

        verify(postRequestedFor(urlEqualTo("/chat/completions"))
            .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_object")))
            .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096"))));
    }

    @Test
    void detectSceneProducts_shouldDropInvalidBboxAndReturnEmptyWhenNoProducts() throws Exception {
        // 一条 bbox 越界（x+width>1）被丢弃，一条合法保留
        String aiJson = """
            {
              "products": [
                {"bbox": {"x": 0.8, "y": 0.1, "width": 0.5, "height": 0.3}, "estimatedCategory": "SF"},
                {"bbox": {"x": 0.1, "y": 0.1, "width": 0.3, "height": 0.3}, "estimatedCategory": "FS", "label": "休闲椅"}
              ]
            }
            """;
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var products = visionService.detectSceneProducts(new ByteArrayInputStream("fake-scene".getBytes()), 12);

        assertThat(products).hasSize(1);
        assertThat(products.get(0).getEstimatedCategory()).isEqualTo("FS");

        // 无家具场景：返回空列表而非报错
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"products\": []}"))));

        var empty = visionService.detectSceneProducts(new ByteArrayInputStream("fake-scene".getBytes()), 12);
        assertThat(empty).isEmpty();
    }

    @Test
    void refineSceneProduct_shouldParseRefinedBbox() throws Exception {
        String aiJson = """
            {
              "isSingleFurniture": true,
              "bbox": {"x": 0.05, "y": 0.1, "width": 0.85, "height": 0.8},
              "estimatedCategory": "SF",
              "label": "三人位沙发"
            }
            """;
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody(aiJson))));

        var refined = visionService.refineSceneProduct(new ByteArrayInputStream("fake-crop".getBytes()));

        assertThat(refined).isNotNull();
        assertThat(refined.getEstimatedCategory()).isEqualTo("SF");
        assertThat(refined.getLabel()).isEqualTo("三人位沙发");
        assertThat(refined.getBbox().getWidth()).isEqualTo(0.85);
    }

    @Test
    void refineSceneProduct_shouldReturnNullWhenNotSingleFurniture() throws Exception {
        stubFor(post(urlEqualTo("/chat/completions"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(buildChatCompletionResponseBody("{\"isSingleFurniture\": false}"))));

        var refined = visionService.refineSceneProduct(new ByteArrayInputStream("fake-crop".getBytes()));

        assertThat(refined).isNull();
    }
}
