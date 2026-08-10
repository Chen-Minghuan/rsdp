package com.rsdp.dto.response;

import lombok.Data;

/**
 * 留资跟进人候选（GET /api/v1/leads/assignees）：平台运营角色（ADMIN/EDITOR）用户。
 */
@Data
public class LeadAssigneeResponse {

    private String username;

    private String nickname;
}
