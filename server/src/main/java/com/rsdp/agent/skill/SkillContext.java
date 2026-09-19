package com.rsdp.agent.skill;

import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.tool.AgentReadTools;

import java.util.List;
import java.util.Map;

/**
 * Skill 执行上下文：输入 + 只读工具门面。
 *
 * @param sessionId      会话 ID
 * @param runId          运行 ID
 * @param constraints    当前需求约束（可空）
 * @param confirmedItems 会话内 status=confirmed 的确认项（创建时间升序）
 * @param input          额外输入参数（Skill 自定义，可空）
 * @param tools          只读工具门面（Skill 唯一的外部出口）
 */
public record SkillContext(String sessionId, String runId,
                           RequirementConstraints constraints,
                           List<AgentConfirmedItem> confirmedItems,
                           Map<String, Object> input,
                           AgentReadTools tools) {
}
