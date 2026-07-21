package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 邀请记录响应。
 */
@Data
public class InviteRecordResponse {

    private Long id;
    private String inviteeId;
    private String inviteeUsername;
    private String inviteeNickname;
    private LocalDateTime createdAt;
}
