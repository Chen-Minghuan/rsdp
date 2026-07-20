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
        // 启动校验：密钥未显式配置时快速失败，避免使用空凭证连接 MinIO
        if (minio.getAccessKey() == null || minio.getAccessKey().isBlank()
            || minio.getSecretKey() == null || minio.getSecretKey().isBlank()) {
            throw new IllegalStateException(
                "MinIO 存储已启用（storage.type=minio），但 accessKey/secretKey 未配置，"
                    + "请通过 MINIO_ACCESS_KEY / MINIO_SECRET_KEY 环境变量注入");
        }
        return MinioClient.builder()
            .endpoint(minio.getEndpoint())
            .credentials(minio.getAccessKey(), minio.getSecretKey())
            .build();
    }
}
