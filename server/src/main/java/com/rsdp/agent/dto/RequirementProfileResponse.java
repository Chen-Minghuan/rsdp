package com.rsdp.agent.dto;

import com.rsdp.agent.patch.RequirementConstraints;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 需求档案响应（与前端 RequirementProfile 契约逐字段对应）。
 *
 * <p>SSE requirement 事件与 GET 会话详情共用此结构（全量刷新语义）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RequirementProfileResponse {

    private Integer versionNo;

    private RequirementConstraints constraints;

    /** extract/followup/manual/feedback。 */
    private String source;
}
