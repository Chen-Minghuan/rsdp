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

    /**
     * 向量维度。需与 embedding 模型输出维度一致
     * （DashScope multimodal-embedding-v1 输出 1024 维）。
     * 创建集合时显式声明，upsert 时校验维度匹配，防止混入错误维度的向量。
     */
    private int dimension = 1024;
}
