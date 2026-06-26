package com.rsdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.rsdp.dto.AiLabels;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link VisionService} 单元测试，使用 WireMock 模拟 DashScope API。
 */
class VisionServiceTest {

    private WireMockServer wireMockServer;
    private VisionService visionService;

    @TempDir
    Path tempDir;

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

        visionService = new VisionService(restClient, new ObjectMapper());
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

        Path imagePath = tempDir.resolve("test.jpg");
        Files.write(imagePath, "fake-image".getBytes());

        AiLabels labels = visionService.recognizeImage(imagePath);

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

        Path imagePath = tempDir.resolve("test.jpg");
        Files.write(imagePath, "fake-image".getBytes());

        assertThatThrownBy(() -> visionService.recognizeImage(imagePath))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("API 返回为空");
    }
}
