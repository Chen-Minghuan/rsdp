package com.rsdp.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Configuration
public class AiApiConfig {

    @Value("${rsdp.ai.base-url}")
    private String baseUrl;

    @Value("${rsdp.ai.api-key}")
    private String apiKey;

    @Value("${rsdp.ai.embedding-base-url}")
    private String embeddingBaseUrl;

    @Value("${rsdp.chromadb.base-url}")
    private String chromaBaseUrl;

    @Value("${rsdp.chromadb.auth-token:}")
    private String chromaAuthToken;

    @Value("${rsdp.ai.timeout-seconds:60}")
    private int aiTimeoutSeconds;

    @Value("${rsdp.ai.max-retries:2}")
    private int aiMaxRetries;

    @Value("${rsdp.ai.embedding-timeout-seconds:30}")
    private int embeddingTimeoutSeconds;

    @Value("${rsdp.ai.embedding-max-retries:2}")
    private int embeddingMaxRetries;

    @Value("${rsdp.chromadb.timeout-seconds:10}")
    private int chromaTimeoutSeconds;

    @Value("${rsdp.chromadb.max-retries:2}")
    private int chromaMaxRetries;

    @Value("${rsdp.ai.mock.enabled:false}")
    private boolean mockEnabled;

    /**
     * 启动时校验 AI API Key，避免使用默认/空值调用外部接口导致 401。
     *
     * <p>显式启用 Mock 模式（rsdp.ai.mock.enabled=true）时跳过校验，
     * 允许无真实 Key 的本地联调。</p>
     */
    @PostConstruct
    public void validateApiKey() {
        if (mockEnabled) {
            return;
        }
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("AI API Key 未配置：请设置环境变量 DASHSCOPE_API_KEY 或在 deploy/.env 中填写有效 Key"
                + "（本地联调可设置 rsdp.ai.mock.enabled=true 使用 Mock 模式）");
        }
        if (apiKey.contains("your-api-key") || apiKey.contains("CHANGE_ME")) {
            throw new IllegalStateException("AI API Key 为占位符：请将 deploy/.env 中的 DASHSCOPE_API_KEY 替换为真实 Key"
                + "（本地联调可设置 rsdp.ai.mock.enabled=true 使用 Mock 模式）");
        }
    }

    @Bean
    public RestClient aiRestClient() {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory(aiTimeoutSeconds))
            .requestInterceptor(new RetryInterceptor(aiMaxRetries))
            .build();
    }

    @Bean
    public RestClient embeddingRestClient() {
        return RestClient.builder()
            .baseUrl(embeddingBaseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .requestFactory(requestFactory(embeddingTimeoutSeconds))
            .requestInterceptor(new RetryInterceptor(embeddingMaxRetries))
            .build();
    }

    @Bean
    public RestClient chromaRestClient() {
        RestClient.Builder builder = RestClient.builder()
            .baseUrl(chromaBaseUrl)
            .requestFactory(requestFactory(chromaTimeoutSeconds))
            .requestInterceptor(new RetryInterceptor(chromaMaxRetries));
        if (chromaAuthToken != null && !chromaAuthToken.isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + chromaAuthToken);
        }
        return builder.build();
    }

    private ClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
