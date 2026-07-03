package com.rsdp.config;

import com.rsdp.config.properties.StorageProperties;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MinIO 客户端配置。
 */
@Configuration
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "storage", name = "type", havingValue = "minio")
public class MinioConfig {

    private final StorageProperties storageProperties;

    @Bean
    public MinioClient minioClient() {
        StorageProperties.Minio minio = storageProperties.getMinio();
        return MinioClient.builder()
            .endpoint(minio.getEndpoint())
            .credentials(minio.getAccessKey(), minio.getSecretKey())
            .build();
    }
}
