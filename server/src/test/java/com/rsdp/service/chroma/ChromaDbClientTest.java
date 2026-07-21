package com.rsdp.service.chroma;

import com.rsdp.exception.ExternalServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * {@link ChromaDbClient} 单元测试：集合查询的异常分支语义。
 */
class ChromaDbClientTest {

    private static final String BASE = "http://localhost:8000";
    private static final String COLLECTIONS =
        BASE + "/api/v2/tenants/default_tenant/databases/default_database/collections";

    private MockRestServiceServer server;
    private ChromaDbClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        ChromaDbProperties properties = new ChromaDbProperties();
        client = new ChromaDbClient(builder.build(), properties);
    }

    @Test
    void upsert_shouldCreateCollectionWhenQueryReturns404() {
        // 集合不存在（404）→ 创建集合 → 执行 upsert
        server.expect(requestTo(COLLECTIONS + "/rsdp_products"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(COLLECTIONS))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("{\"id\":\"col-123\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(COLLECTIONS + "/col-123/upsert"))
            .andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess());

        assertThatCode(() -> client.upsert(
            List.of("img-1"), List.of(new float[]{0.1f}), List.of(Map.of("rspu_id", "RSPU-1")), null))
            .doesNotThrowAnyException();
        server.verify();
    }

    @Test
    void upsert_shouldThrowWhenCollectionQueryFailsWithServerError() {
        // 集合查询返回 5xx：不是「不存在」，应抛出外部服务异常而不是尝试创建
        server.expect(requestTo(COLLECTIONS + "/rsdp_products"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withServerError());

        assertThatThrownBy(() -> client.upsert(
            List.of("img-1"), List.of(new float[]{0.1f}), List.of(Map.of()), null))
            .isInstanceOf(ExternalServiceException.class)
            .hasMessageContaining("ChromaDB 查询集合失败");
        server.verify();
    }
}
