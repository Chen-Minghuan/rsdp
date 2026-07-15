package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单邀请链接响应。
 */
@Data
@AllArgsConstructor
public class InviteTokenResponse {

    /** 邀请 token（一次性，重新生成后旧链接立即失效） */
    private String token;
    /** 链接过期时间 */
    private LocalDateTime expireAt;
}
