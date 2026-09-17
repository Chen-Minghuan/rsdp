package com.rsdp.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息请求（SSE 流式，与前端 SendAgentMessageRequest 契约对应）。
 */
@Data
public class SendMessageRequest {

    /** 客户端幂等 ID（会话内唯一，重发安全）。 */
    @NotBlank(message = "clientMessageId 不能为空")
    @Size(max = 64, message = "clientMessageId 最长 64 字符")
    private String clientMessageId;

    /** 用户消息内容。 */
    @NotBlank(message = "content 不能为空")
    @Size(max = 4000, message = "content 最长 4000 字符")
    private String content;
}
