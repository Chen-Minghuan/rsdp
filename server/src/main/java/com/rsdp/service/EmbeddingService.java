package com.rsdp.service;

import com.rsdp.dto.DashScopeEmbeddingRequest;
import com.rsdp.dto.DashScopeEmbeddingResponse;
import com.rsdp.exception.ExternalServiceException;
import com.rsdp.util.ImageResizer;
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
     * <p>送 Embedding API 前会将长边超过 1024px 的图片等比缩放为 JPEG，
     * 减少传输体积；缩放失败时降级使用原图，不阻断流程。</p>
     *
     * @param imageStream 图片输入流
     * @return 浮点向量
     */
    public float[] embedImage(InputStream imageStream) {
        try (imageStream) {
            byte[] bytes = imageStream.readAllBytes();
            if (bytes.length == 0) {
                throw new ExternalServiceException("图片流为空");
            }
            String base64 = Base64.getEncoder().encodeToString(resizeIfNecessary(bytes));
            return embedImageBase64(base64);
        } catch (IOException e) {
            log.error("读取图片流失败", e);
            throw new ExternalServiceException("读取图片流失败", e);
        }
    }

    private byte[] resizeIfNecessary(byte[] bytes) {
        try {
            return ImageResizer.resizeToJpeg(bytes, ImageResizer.DEFAULT_MAX_DIMENSION, ImageResizer.DEFAULT_JPEG_QUALITY);
        } catch (Exception e) {
            log.warn("图片缩放失败，降级使用原图生成 embedding: {}", e.getMessage());
            return bytes;
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
        DashScopeEmbeddingResponse response;
        try {
            response = embeddingRestClient.post()
                .body(request)
                .retrieve()
                .body(DashScopeEmbeddingResponse.class);
        } catch (Exception e) {
            throw new ExternalServiceException("Embedding API 调用失败: " + e.getMessage(), e);
        }
        long cost = System.currentTimeMillis() - start;
        log.info("Embedding 生成完成，耗时 {}ms", cost);

        if (response == null || response.getOutput() == null
            || response.getOutput().getEmbeddings() == null
            || response.getOutput().getEmbeddings().isEmpty()) {
            throw new ExternalServiceException("Embedding API 返回为空");
        }

        List<Double> values = response.getOutput().getEmbeddings().get(0).getEmbedding();
        if (values == null || values.isEmpty()) {
            throw new ExternalServiceException("Embedding API 返回向量值为空");
        }
        float[] result = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            result[i] = values.get(i).floatValue();
        }
        return result;
    }
}
