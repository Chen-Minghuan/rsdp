package com.rsdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

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

    @Bean
    public RestClient aiRestClient() {
        return RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    @Bean
    public RestClient embeddingRestClient() {
        return RestClient.builder()
            .baseUrl(embeddingBaseUrl)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
    }

    @Bean
    public RestClient chromaRestClient() {
        return RestClient.builder()
            .baseUrl(chromaBaseUrl)
            .build();
    }
}
