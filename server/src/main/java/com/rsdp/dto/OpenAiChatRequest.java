package com.rsdp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OpenAiChatRequest {
    private String model;
    private List<OpenAiChatMessage> messages;
    private Double temperature;

    /** 最大输出 token 数，防止 AI 无限输出或意外截断。 */
    @JsonProperty("max_tokens")
    private Integer maxTokens;

    /** 响应格式约束，JSON 输出场景使用 {@code {"type": "json_object"}}。 */
    @JsonProperty("response_format")
    private ResponseFormat responseFormat;

    /**
     * OpenAI 兼容接口的响应格式约束。
     */
    @Data
    @Builder
    public static class ResponseFormat {
        private String type;
    }
}
