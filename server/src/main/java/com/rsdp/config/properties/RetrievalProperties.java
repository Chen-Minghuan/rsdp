package com.rsdp.config.properties;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 检索服务配置属性。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "rsdp.retrieval")
public class RetrievalProperties {

    /**
     * 以图搜图结果六维一致度 rerank 开关。
     *
     * <p>开启后，查询图 AI 六维标签与候选 RSPU 六维标签按维度做枚举归一比较
     * （同维度相同计 1 分，加权求和）作为次要排序加成；默认关闭，灰度验证后开启。</p>
     */
    private boolean sixDimRerankEnabled = false;
}
