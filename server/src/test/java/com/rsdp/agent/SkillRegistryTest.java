package com.rsdp.agent;

import com.rsdp.agent.skill.AgentSkill;
import com.rsdp.agent.skill.SkillContext;
import com.rsdp.agent.skill.SkillMetadata;
import com.rsdp.agent.skill.SkillRegistry;
import com.rsdp.agent.skill.SkillResult;
import com.rsdp.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SkillRegistry} 单元测试（注册/查找/重复 id 守卫）。
 */
class SkillRegistryTest {

    private AgentSkill stubSkill(String id) {
        return new AgentSkill() {
            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(id, "1.0.0", "测试", "LOW",
                    Set.of("search_products"), Set.of("agent:use"), Map.of(), Map.of(), 4);
            }

            @Override
            public SkillResult execute(SkillContext ctx) {
                return SkillResult.empty(Map.of());
            }
        };
    }

    @Test
    void requireShouldReturnRegisteredSkill() {
        SkillRegistry registry = new SkillRegistry(List.of(stubSkill("living-room-matching")));

        assertThat(registry.require("living-room-matching").metadata().id())
            .isEqualTo("living-room-matching");
    }

    @Test
    void requireUnknownSkillShouldThrow() {
        SkillRegistry registry = new SkillRegistry(List.of(stubSkill("living-room-matching")));

        assertThatThrownBy(() -> registry.require("not-exists"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Skill 未注册");
    }

    @Test
    void duplicateSkillIdShouldFailFast() {
        assertThatThrownBy(() -> new SkillRegistry(List.of(stubSkill("dup"), stubSkill("dup"))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Skill id 重复注册");
    }

    @Test
    void listShouldReturnAllMetadata() {
        SkillRegistry registry = new SkillRegistry(List.of(stubSkill("a"), stubSkill("b")));

        assertThat(registry.list()).extracting(SkillMetadata::id)
            .containsExactlyInAnyOrder("a", "b");
    }
}
