package com.rsdp.config.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "storage")
public class StorageProperties {

    /**
     * 存储类型：local / minio。
     */
    private String type = "local";

    /**
     * 本地存储根目录（type=local 时生效）。
     */
    private String localPath = "./data/uploads";

    /**
     * MinIO 配置（type=minio 时生效）。
     */
    private Minio minio = new Minio();

    /**
     * MinIO 配置子属性。
     *
     * <p>accessKey/secretKey 默认留空，启用 minio 存储时必须显式配置
     * （生产经环境变量注入），启动时由 {@code MinioConfig} 校验。</p>
     */
    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "";
        private String secretKey = "";
        private String bucketName = "rsdp";
    }
}
