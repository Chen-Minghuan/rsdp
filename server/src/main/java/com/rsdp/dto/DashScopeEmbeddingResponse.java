package com.rsdp.dto;

import lombok.Data;

import java.util.List;

/**
 * DashScope 多模态 embedding 响应。
 */
@Data
public class DashScopeEmbeddingResponse {

    private Output output;

    @Data
    public static class Output {
        private List<Embedding> embeddings;
    }

    @Data
    public static class Embedding {
        private List<Double> embedding;
    }
}
