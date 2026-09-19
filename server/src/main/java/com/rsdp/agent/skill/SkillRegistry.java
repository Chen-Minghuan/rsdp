package com.rsdp.agent.skill;

import com.rsdp.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Skill 注册表：汇总全部 {@link AgentSkill} Bean，按 id 索引。
 *
 * <p>启动时打印全量元数据（可追溯）；id 重复直接启动失败（尽早暴露配置错误）。</p>
 */
@Slf4j
@Component
public class SkillRegistry {

    private final Map<String, AgentSkill> skills;

    public SkillRegistry(List<AgentSkill> skillBeans) {
        this.skills = skillBeans.stream().collect(Collectors.toMap(
            skill -> skill.metadata().id(),
            Function.identity(),
            (a, b) -> {
                throw new IllegalStateException("Skill id 重复注册: " + a.metadata().id());
            }));
        skills.values().forEach(skill -> {
            SkillMetadata meta = skill.metadata();
            log.info("注册 Skill：{}@{}（riskLevel={}, allowedTools={}, requiredPermissions={}, maxSteps={}）",
                meta.id(), meta.version(), meta.riskLevel(), meta.allowedTools(),
                meta.requiredPermissions(), meta.maxSteps());
        });
    }

    /** 按 id 取 Skill；未注册抛错。 */
    public AgentSkill require(String skillId) {
        AgentSkill skill = skills.get(skillId);
        if (skill == null) {
            throw new BusinessException("Skill 未注册: " + skillId);
        }
        return skill;
    }

    /** 全量 Skill 元数据。 */
    public List<SkillMetadata> list() {
        return skills.values().stream().map(AgentSkill::metadata).toList();
    }
}
