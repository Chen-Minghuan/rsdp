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
        return vision(role, text, base64Image, null, null);
    }

    /**
     * 构建单图 vision 消息，可选携带 DashScope 像素预算参数。
     *
     * <p>min_pixels/max_pixels 是 qwen-vl 系列的非标准参数，实测必须放在
     * content 的 image_url 部分内（与 image_url 平级）才生效；放在请求顶层
     * 会被端点静默忽略（2026-09 实测验证）。为 null 时不输出该字段。</p>
     *
     * @param role         消息角色
     * @param text         文本提示
     * @param base64Image  base64 编码图片
     * @param minPixels    输入图像最小像素阈值（低于则放大），可空
     * @param maxPixels    输入图像最大像素阈值（高于则缩小），可空
     * @return vision 消息
     */
    public static OpenAiChatMessage vision(String role, String text, String base64Image,
                                           Integer minPixels, Integer maxPixels) {
        OpenAiChatMessage msg = new OpenAiChatMessage();
        msg.setRole(role);
        msg.setContent(List.of(
            imagePart(base64Image, minPixels, maxPixels),
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
        return multiVision(role, text, base64Images, null, null);
    }

    /**
     * 构建包含多张图片的 vision 消息，每张图可选携带像素预算参数（语义同
     * {@link #vision(String, String, String, Integer, Integer)}）。
     *
     * @param role         消息角色
     * @param text         文本提示
     * @param base64Images base64 编码的图片列表
     * @param minPixels    输入图像最小像素阈值，可空
     * @param maxPixels    输入图像最大像素阈值，可空
     * @return 多图 vision 消息
     */
    public static OpenAiChatMessage multiVision(String role, String text, List<String> base64Images,
                                                Integer minPixels, Integer maxPixels) {
        OpenAiChatMessage msg = new OpenAiChatMessage();
        List<Map<String, Object>> content = new java.util.ArrayList<>();
        for (String base64 : base64Images) {
            content.add(imagePart(base64, minPixels, maxPixels));
        }
        content.add(Map.of("type", "text", "text", text));
        msg.setRole(role);
        msg.setContent(content);
        return msg;
    }

    /** 构建 image_url 内容部分；minPixels/maxPixels 非空时作为平级字段输出（DashScope qwen-vl 扩展）。 */
    private static Map<String, Object> imagePart(String base64Image, Integer minPixels, Integer maxPixels) {
        Map<String, Object> part = new java.util.HashMap<>();
        part.put("type", "image_url");
        part.put("image_url", Map.of("url", "data:image/jpeg;base64," + base64Image));
        if (minPixels != null) {
            part.put("min_pixels", minPixels);
        }
        if (maxPixels != null) {
            part.put("max_pixels", maxPixels);
        }
        return part;
    }
}
