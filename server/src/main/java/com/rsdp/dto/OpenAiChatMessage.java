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

    /**
     * 构建包含多张图片的 vision 消息。
     *
     * @param role         消息角色
     * @param text         文本提示
     * @param base64Images base64 编码的图片列表
     * @return 多图 vision 消息
     */
    public static OpenAiChatMessage multiVision(String role, String text, List<String> base64Images) {
        OpenAiChatMessage msg = new OpenAiChatMessage();
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        for (String base64 : base64Images) {
            content.add(Map.of("type", "image_url", "image_url", Map.of("url", "data:image/jpeg;base64," + base64)));
        }
        content.add(Map.of("type", "text", "text", text));
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }
}
