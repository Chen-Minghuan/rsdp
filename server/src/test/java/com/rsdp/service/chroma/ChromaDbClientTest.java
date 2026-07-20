package com.rsdp.service.chroma;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.rsdp.exception.ExternalServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.configureFor;
import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ChromaDbClient} 单元测试，使用 WireMock 模拟 ChromaDB REST API。
 */
class ChromaDbClientTest {

    private static final String COLLECTIONS = "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private WireMockServer wireMockServer;
    private ChromaDbClient chromaDbClient;

    @BeforeEach
    void setUp() {
        wireMockServer = new WireMockServer(0);
        wireMockServer.start();
        configureFor(wireMockServer.port());

        RestClient restClient = RestClient.builder()
            .baseUrl(wireMockServer.baseUrl())
            .requestFactory(new SimpleClientHttpRequestFactory())
            .build();

        ChromaDbProperties properties = new ChromaDbProperties();
        properties.setCollection("rsdp_products");
        chromaDbClient = new ChromaDbClient(restClient, properties);
    }

    @AfterEach
    void tearDown() {
        wireMockServer.stop();
    }

    private void stubGetCollection(String id) {
        stubFor(get(urlEqualTo(COLLECTIONS + "/rsdp_products"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"" + id + "\",\"name\":\"rsdp_products\"}")));
    }

    private void upsertOne() {
        chromaDbClient.upsert(
            List.of("IMG-001"),
            List.of(new float[]{0.1f, 0.2f}),
            List.of(Map.of("rspu_id", "RSPU-001")),
            null);
    }

    @Test
    void upsert_shouldSucceedAndCacheCollectionId() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .willReturn(aResponse().withStatus(200)));

        upsertOne();
        upsertOne();

        // 集合 ID 只解析一次（走缓存），upsert 执行两次
        verify(exactly(1), getRequestedFor(urlEqualTo(COLLECTIONS + "/rsdp_products")));
        verify(exactly(2), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/upsert")));
    }

    @Test
    void upsert_shouldClearCacheAndRetryOnceWhenCollectionNotFound() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404).withBody("Collection does not exist"))
            .willSetStateTo("RETRIED"));
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .inScenario("retry")
            .whenScenarioStateIs("RETRIED")
            .willReturn(aResponse().withStatus(200)));

        upsertOne();

        // 缓存被清除后重新解析了一次集合 ID
        verify(exactly(2), getRequestedFor(urlEqualTo(COLLECTIONS + "/rsdp_products")));
        verify(exactly(2), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/upsert")));
    }

    @Test
    void upsert_shouldRetryWhenErrorMessageContainsDoesNotExist() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(400).withBody("{\"error\":\"Collection rsdp_products does not exist\"}"))
            .willSetStateTo("RETRIED"));
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .inScenario("retry")
            .whenScenarioStateIs("RETRIED")
            .willReturn(aResponse().withStatus(200)));

        upsertOne();

        verify(exactly(2), getRequestedFor(urlEqualTo(COLLECTIONS + "/rsdp_products")));
        verify(exactly(2), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/upsert")));
    }

    @Test
    void upsert_shouldThrowWhenRetryStillFails() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .willReturn(aResponse().withStatus(404).withBody("Collection does not exist")));

        assertThatThrownBy(this::upsertOne)
            .isInstanceOf(ExternalServiceException.class)
            .hasMessageContaining("upsert 失败");

        // 仅重试一次，共两次请求
        verify(exactly(2), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/upsert")));
    }

    @Test
    void upsert_shouldNotRetryForNonCollectionNotFoundErrors() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/upsert"))
            .willReturn(aResponse().withStatus(500).withBody("internal error")));

        assertThatThrownBy(this::upsertOne)
            .isInstanceOf(ExternalServiceException.class)
            .hasMessageContaining("upsert 失败");

        verify(exactly(1), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/upsert")));
    }

    @Test
    void delete_shouldClearCacheAndRetryOnceWhenCollectionNotFound() {
        stubGetCollection("col-1");
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/delete"))
            .inScenario("retry")
            .whenScenarioStateIs(Scenario.STARTED)
            .willReturn(aResponse().withStatus(404).withBody("Collection does not exist"))
            .willSetStateTo("RETRIED"));
        stubFor(post(urlEqualTo(COLLECTIONS + "/col-1/delete"))
            .inScenario("retry")
            .whenScenarioStateIs("RETRIED")
            .willReturn(aResponse().withStatus(200)));

        chromaDbClient.delete(List.of("IMG-001"));

        verify(exactly(2), getRequestedFor(urlEqualTo(COLLECTIONS + "/rsdp_products")));
        verify(exactly(2), postRequestedFor(urlEqualTo(COLLECTIONS + "/col-1/delete")));
    }
}
