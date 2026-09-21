package com.rsdp.agent.skill;

/**
 * 营销 Agent Skill：完成某类专业任务的确定性策略/流程（Graph≠Skill≠Tool 分层中的 Skill 层）。
 *
 * <p>实现为 Spring Bean 并由 {@link SkillRegistry} 汇总。权限铁律：Skill 只能经
 * {@link SkillContext#tools()} 调读工具；写操作（confirm/quote/scheme/order）归 Graph + HITL，
 * 写能力不在 Skill 可见的类型系统内出现。</p>
 */
public interface AgentSkill {

    /** Skill 元数据（id/version/allowedTools/requiredPermissions 等）。 */
    SkillMetadata metadata();

    /**
     * 确定性执行。
     *
     * @param ctx 执行上下文（会话/约束/确认清单 + 只读工具门面）
     * @return 执行结果（分组候选 + 留痕 trace）
     */
    SkillResult execute(SkillContext ctx);
}
