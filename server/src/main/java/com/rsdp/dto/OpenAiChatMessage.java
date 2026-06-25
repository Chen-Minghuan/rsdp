package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenAiChatMessage {
    private String role;
    private Object content;

    public static OpenAiChatMessage text(String role, String text) {
        OpenAiChatMessage msg = new OpenAiChatMessage();
        msg.setRole(role);
        msg.setContent(text);
        return msg;
    }

    public static OpenAiChatMessage vision(String role, String text, String base64Image) {
        OpenAiChatMessage msg = new OpenAiChatMessage();
        msg.setRole(role);
        msg.setContent(List.of(
            Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + base64Image)),
            Map.of("type", "text", "text", text)
        ));
        return msg;
    }
}
