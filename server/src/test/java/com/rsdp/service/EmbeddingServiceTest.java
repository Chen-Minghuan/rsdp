package com.rsdp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link EmbeddingService} 单元测试，使用 WireMock 模拟 DashScope Embedding API。
 */
class EmbeddingServiceTest {

    private WireMockServer wireMockServer;
    private EmbeddingService embeddingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        configureFor(wireMockServer.port());

        RestClient restClient = RestClient.builder()
            .baseUrl(wireMockServer.baseUrl())
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();

        embeddingService = new EmbeddingService(restClient);
        java.lang.reflect.Field field = EmbeddingService.class.getDeclaredField("embeddingModel");
        field.setAccessible(true);
        field.set(embeddingService, "multimodal-embedding-v1");

        stubFor(post(urlEqualTo("/"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"output\":{\"embeddings\":[{\"embedding\":[0.1,0.2,0.3]}]}}")));
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private byte[] createPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private byte[] extractSentImageBytes() throws Exception {
        String requestBody = wireMockServer.getAllServeEvents().get(0).getRequest().getBodyAsString();
        JsonNode root = objectMapper.readTree(requestBody);
        String dataUri = root.at("/input/contents/0/image").asText();
        assertThat(dataUri).startsWith("data:image/jpeg;base64,");
        return Base64.getDecoder().decode(dataUri.substring("data:image/jpeg;base64,".length()));
    }

    @Test
    void embedImage_shouldResizeLargeImageBeforeSending() throws Exception {
        byte[] largeImage = createPng(2048, 1024);

        float[] vector = embeddingService.embedImage(new ByteArrayInputStream(largeImage));

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        byte[] sentBytes = extractSentImageBytes();
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(sentBytes));
        assertThat(decoded).isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(1024);
        assertThat(decoded.getHeight()).isEqualTo(512);
    }

    @Test
    void embedImage_shouldKeepSmallImageAsIs() throws Exception {
        byte[] smallImage = createPng(800, 600);

        embeddingService.embedImage(new ByteArrayInputStream(smallImage));

        byte[] sentBytes = extractSentImageBytes();
        assertThat(sentBytes).isEqualTo(smallImage);
    }

    @Test
    void embedImage_shouldFallbackToOriginalBytesWhenResizeFails() throws Exception {
        byte[] invalidImage = "not-a-valid-image".getBytes();

        float[] vector = embeddingService.embedImage(new ByteArrayInputStream(invalidImage));

        assertThat(vector).containsExactly(0.1f, 0.2f, 0.3f);
        byte[] sentBytes = extractSentImageBytes();
        assertThat(sentBytes).isEqualTo(invalidImage);
    }
}
