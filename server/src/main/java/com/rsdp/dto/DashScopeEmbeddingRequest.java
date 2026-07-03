package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * DashScope 多模态 embedding 请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashScopeEmbeddingRequest {

    private String model;
    private Input input;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Input {
        private List<Map<String, String>> contents;
    }
}
