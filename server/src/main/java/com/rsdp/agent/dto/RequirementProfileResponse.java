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

    /** 有效约束视图（全局 + 当前房间合并；户型会话随 activeRoom 变化）。 */
    private RequirementConstraints constraints;

    /** extract/followup/manual/feedback。 */
    private String source;

    /** 当前房间 ID（W2，无户型/未选择为 null）。 */
    private String roomId;

    /** 当前房间展示名（W2，无户型/未选择为 null）。 */
    private String roomName;

    public RequirementProfileResponse(Integer versionNo, RequirementConstraints constraints, String source) {
        this.versionNo = versionNo;
        this.constraints = constraints;
        this.source = source;
    }
}
