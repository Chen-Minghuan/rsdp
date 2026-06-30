package com.rsdp.service.chroma;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * ChromaDB 配置属性。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rsdp.chromadb")
public class ChromaDbProperties {

    /**
     * ChromaDB 服务地址。
     */
    private String baseUrl = "http://localhost:8000";

    /**
     * 集合名称。
     */
    private String collection = "rsdp_products";
}
