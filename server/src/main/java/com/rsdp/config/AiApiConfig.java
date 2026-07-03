package com.rsdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
        return RestClient.builder()
            .baseUrl(chromaBaseUrl)
            .requestFactory(requestFactory(chromaTimeoutSeconds))
            .requestInterceptor(new RetryInterceptor(chromaMaxRetries))
            .build();
    }

    private ClientHttpRequestFactory requestFactory(int timeoutSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofSeconds(timeoutSeconds);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return factory;
    }
}
