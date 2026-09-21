package com.rsdp.agent.skill;

import com.rsdp.agent.domain.ProductSearchItem;

import java.util.List;
import java.util.Map;

/**
 * Skill 执行结果。
 *
 * @param groups 配套分组（按品类聚合的候选）
 * @param trace  留痕信息（锚点/规则版本/预算切片等，写入批次 query_criteria 供追溯）
 */
public record SkillResult(List<CompanionGroup> groups, Map<String, Object> trace) {

    /** 空结果（无规则命中或无候选）。 */
    public static SkillResult empty(Map<String, Object> trace) {
        return new SkillResult(List.of(), trace);
    }

    /**
     * 配套分组。
     *
     * @param categoryCode  品类码（TB/FS/FC 等），落 agent_recommend_item.group_tag
     * @param categoryName  品类中文名（展示用）
     * @param items         候选产品（pinned 关系产品已置组首）
     * @param pinnedRspuIds 组内置顶的 rspu_relation 关系产品 ID（official/ai_verified）
     */
    public record CompanionGroup(String categoryCode, String categoryName,
                                 List<ProductSearchItem> items, List<String> pinnedRspuIds) {
    }
}
