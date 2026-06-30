package com.rsdp.service;

import com.rsdp.dto.DashScopeEmbeddingRequest;
import com.rsdp.dto.DashScopeEmbeddingResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * 图片/文本 embedding 服务。
 * <p>当前使用 DashScope 多模态 embedding API。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final RestClient embeddingRestClient;

    @Value("${rsdp.ai.embedding-model}")
    private String embeddingModel;

    /**
     * 根据图片流生成 embedding 向量。
     *
     * @param imageStream 图片输入流
     * @return 浮点向量
     */
    public float[] embedImage(InputStream imageStream) {
        try {
            byte[] bytes = imageStream.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return embedImageBase64(base64);
        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new RuntimeException("读取图片流失败", e);
        }
    }

    /**
     * 根据图片 Base64 生成 embedding 向量。
     *
     * @param base64 图片 Base64 编码（不含 data URI 前缀）
     * @return 浮点向量
     */
    public float[] embedImageBase64(String base64) {
        DashScopeEmbeddingRequest.Input input = new DashScopeEmbeddingRequest.Input(
            List.of(Map.of("image", "data:image/jpeg;base64," + base64))
        );
        DashScopeEmbeddingRequest request = new DashScopeEmbeddingRequest(embeddingModel, input);
        return executeEmbedding(request);
    }

    /**
     * 根据文本生成 embedding 向量。
     *
     * @param text 文本
     * @return 浮点向量
     */
    public float[] embedText(String text) {
        DashScopeEmbeddingRequest.Input input = new DashScopeEmbeddingRequest.Input(
            List.of(Map.of("text", text))
        );
        DashScopeEmbeddingRequest request = new DashScopeEmbeddingRequest(embeddingModel, input);
        return executeEmbedding(request);
    }

    private float[] executeEmbedding(DashScopeEmbeddingRequest request) {
        long start = System.currentTimeMillis();
        DashScopeEmbeddingResponse response = embeddingRestClient.post()
            .body(request)
            .retrieve()
            .body(DashScopeEmbeddingResponse.class);
        long cost = System.currentTimeMillis() - start;
        log.info("Embedding 生成完成，耗时 {}ms", cost);

        if (response == null || response.getOutput() == null
            || response.getOutput().getEmbeddings() == null
            || response.getOutput().getEmbeddings().isEmpty()) {
            throw new RuntimeException("Embedding API 返回为空");
        }

        List<Double> values = response.getOutput().getEmbeddings().get(0).getEmbedding();
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }
}
