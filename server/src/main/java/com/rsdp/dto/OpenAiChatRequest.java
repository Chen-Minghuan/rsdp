package com.rsdp.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class OpenAiChatRequest {
    private String model;
    private List<OpenAiChatMessage> messages;
    private Double temperature;
}
