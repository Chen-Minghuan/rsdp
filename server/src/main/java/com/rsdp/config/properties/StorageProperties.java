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
     */
    @Data
    public static class Minio {
        private String endpoint = "http://localhost:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucketName = "rsdp";
    }
}
