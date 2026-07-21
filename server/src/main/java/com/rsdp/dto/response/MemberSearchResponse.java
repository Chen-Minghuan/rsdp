package com.rsdp.dto.response;

import lombok.Data;

/**
 * 成员搜索（邀请候选）响应。
 */
@Data
public class MemberSearchResponse {

    private String userId;
    private String username;
    private String nickname;
    private String status;
}
