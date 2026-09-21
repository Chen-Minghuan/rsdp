package com.rsdp.agent.skill;

import java.util.Map;
import java.util.Set;

/**
 * Skill 元数据（架构文档 §2.2 约定）。
 *
 * <p>每次推荐可追溯「用了哪个 skill 哪个版本、哪些 tool」：
 * 批次落库 agent_recommend_batch.skill_id / skill_version 即来源于此。</p>
 *
 * @param id                  Skill 唯一标识（如 living-room-matching）
 * @param version             版本号（如 1.0.0）
 * @param description         用途描述
 * @param riskLevel           风险等级（LOW/MEDIUM/HIGH），配套推荐类为 LOW
 * @param allowedTools        允许调用的读工具 ID 集合（权限铁律：Skill 只能调读工具）
 * @param requiredPermissions 执行所需权限点（由挂 Skill 的 Graph 节点执行前校验）
 * @param inputSchema         输入结构说明（留痕/文档用途）
 * @param outputSchema        输出结构说明（留痕/文档用途）
 * @param maxSteps            最大执行步数（工具调用次数上限）
 */
public record SkillMetadata(String id, String version, String description,
                            String riskLevel, Set<String> allowedTools,
                            Set<String> requiredPermissions,
                            Map<String, Object> inputSchema,
                            Map<String, Object> outputSchema,
                            int maxSteps) {
}
