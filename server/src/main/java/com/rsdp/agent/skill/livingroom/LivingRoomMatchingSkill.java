package com.rsdp.agent.skill.livingroom;

import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.skill.AgentSkill;
import com.rsdp.agent.skill.SkillContext;
import com.rsdp.agent.skill.SkillMetadata;
import com.rsdp.agent.skill.SkillResult;
import com.rsdp.agent.tool.AgentReadTools;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * LV2 客厅配套搭配 Skill（living-room-matching）。
 *
 * <p>以会话内最新确认的主体产品（通常 SF 沙发）为锚点，按品类规则 + rspu_relation
 * 官方/AI 确认搭配关系生成配套候选（茶几/柜类/休闲椅等）。全程确定性，LLM 零参与选品：
 * 风格/颜色/材质继承需求档案，预算按规则权重切片，互斥关系与已确认产品剔除，
 * 置顶关系产品（过可见性过滤后）置组首。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LivingRoomMatchingSkill implements AgentSkill {

    public static final String SKILL_ID = "living-room-matching";
    public static final String SKILL_VERSION = "1.0.0";

    private static final SkillMetadata METADATA = new SkillMetadata(
        SKILL_ID, SKILL_VERSION,
        "LV2 客厅配套搭配：以已确认主体产品为锚点，推荐茶几/柜类/休闲椅等配套产品",
        "LOW",
        Set.of(AgentReadTools.TOOL_SEARCH_PRODUCTS, AgentReadTools.TOOL_LIST_RELATIONS,
            AgentReadTools.TOOL_FIND_PRODUCTS),
        Set.of("agent:use"),
        Map.of("anchor", "最新确认项（confirmedItem）", "constraints", "需求约束（风格/颜色/材质/预算）"),
        Map.of("groups", "按品类分组的配套候选（pinned 关系产品置组首）", "trace", "锚点/预算切片留痕"),
        4);

    private final MarketingAgentProperties properties;

    @Override
    public SkillMetadata metadata() {
        return METADATA;
    }

    @Override
    public SkillResult execute(SkillContext ctx) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("skillId", SKILL_ID);
        trace.put("skillVersion", SKILL_VERSION);

        List<AgentConfirmedItem> confirmed = ctx.confirmedItems() != null ? ctx.confirmedItems() : List.of();
        if (confirmed.isEmpty()) {
            trace.put("reason", "no_confirmed_item");
            return SkillResult.empty(trace);
        }

        // 1. 锚点 = 最新确认项（列表按创建时间升序，取末尾）
        AgentConfirmedItem anchor = confirmed.get(confirmed.size() - 1);
        trace.put("anchorRspuId", anchor.getRspuId());
        RspuMaster anchorProduct = ctx.tools().findProducts(List.of(anchor.getRspuId())).get(anchor.getRspuId());
        if (anchorProduct == null || !StringUtils.hasText(anchorProduct.getCategoryCode())) {
            trace.put("reason", "anchor_not_found");
            return SkillResult.empty(trace);
        }
        String anchorCategory = anchorProduct.getCategoryCode().trim();
        trace.put("anchorCategory", anchorCategory);

        // 2. 品类规则：锚点品类无规则 → 空结果（由节点给兜底文案）
        List<MarketingAgentProperties.CompanionRule> rules =
            properties.getCompanionRules().getOrDefault(anchorCategory, List.of());
        if (rules.isEmpty()) {
            trace.put("reason", "no_rule_for_category");
            return SkillResult.empty(trace);
        }

        // 3. 剔除集合：锚点自身 + 全部已确认产品 + 互斥关系
        Set<String> excluded = new HashSet<>(ctx.tools().listExcludedRspuIds(anchor.getRspuId()));
        confirmed.forEach(item -> excluded.add(item.getRspuId()));

        // 4. 置顶关系产品（official/ai_verified，过可见性过滤后按品类归组）
        List<RspuRelation> relations = ctx.tools().listCompanionRelations(anchor.getRspuId());
        List<String> pinnedIds = relations.stream()
            .map(RspuRelation::getRelatedRspuId)
            .filter(id -> !excluded.contains(id))
            .distinct()
            .toList();
        Map<String, List<ProductSearchItem>> pinnedByCategory = new HashMap<>();
        if (!pinnedIds.isEmpty()) {
            Map<String, RspuMaster> pinnedProducts = ctx.tools().findProducts(pinnedIds);
            List<ProductSearchItem> pinnedItems = ctx.tools().findProductItems(pinnedIds);
            for (ProductSearchItem item : pinnedItems) {
                RspuMaster product = pinnedProducts.get(item.getRspuId());
                if (product != null && StringUtils.hasText(product.getCategoryCode())) {
                    pinnedByCategory.computeIfAbsent(product.getCategoryCode().trim(), k -> new ArrayList<>())
                        .add(item);
                }
            }
        }

        // 5. 预算切片：剩余预算（budgetMax − 锚点零售参考价）按规则权重分配
        RequirementConstraints constraints = ctx.constraints();
        Map<String, BigDecimal> budgetSplit = splitBudget(
            constraints != null ? constraints.getBudgetMax() : null,
            anchorProduct.getRetailPrice(), rules);
        trace.put("budgetSplit", budgetSplit.isEmpty() ? null : budgetSplit.toString());

        // 6. 逐品类检索（步数上限 maxSteps）：约束继承 + 预算切片 + 剔除 + pinned 置组首
        List<SkillResult.CompanionGroup> groups = new ArrayList<>();
        int steps = 0;
        for (MarketingAgentProperties.CompanionRule rule : rules) {
            if (steps >= METADATA.maxSteps()) {
                trace.put("truncated", "max_steps_reached");
                break;
            }
            if (!StringUtils.hasText(rule.getCategoryCode())) {
                continue;
            }
            String categoryCode = rule.getCategoryCode().trim();
            List<ProductSearchItem> pinned = pinnedByCategory.getOrDefault(categoryCode, List.of()).stream()
                .filter(item -> !excluded.contains(item.getRspuId()))
                .toList();

            ProductSearchCriteria criteria = new ProductSearchCriteria();
            criteria.setCategoryCode(categoryCode);
            if (constraints != null) {
                criteria.setStyle(constraints.getStyle());
                criteria.setMaterial(constraints.getMaterial());
                criteria.setColor(constraints.getColor());
            }
            BigDecimal slice = budgetSplit.get(categoryCode);
            if (slice != null) {
                criteria.setBudgetMax(slice);
            }
            int max = Math.max(rule.getMax(), 1);
            criteria.setTopN(max + pinned.size());

            steps++;
            ProductSearchResult result;
            try {
                result = ctx.tools().searchProducts(criteria);
            } catch (Exception e) {
                log.warn("配套品类检索失败，跳过该品类，category={}, runId={}", categoryCode, ctx.runId(), e);
                continue;
            }
            List<ProductSearchItem> searched = result != null && result.getItems() != null
                ? result.getItems() : List.of();
            Set<String> pinnedIdSet = new HashSet<>();
            List<ProductSearchItem> items = new ArrayList<>(pinned);
            pinned.forEach(item -> pinnedIdSet.add(item.getRspuId()));
            for (ProductSearchItem item : searched) {
                if (items.size() >= max + pinned.size()) {
                    break;
                }
                if (excluded.contains(item.getRspuId()) || pinnedIdSet.contains(item.getRspuId())) {
                    continue;
                }
                items.add(item);
            }
            if (items.isEmpty()) {
                continue;
            }
            groups.add(new SkillResult.CompanionGroup(
                categoryCode,
                StringUtils.hasText(rule.getCategoryName()) ? rule.getCategoryName() : categoryCode,
                items,
                pinned.stream().map(ProductSearchItem::getRspuId).toList()));
        }
        trace.put("steps", steps);
        return new SkillResult(groups, trace);
    }

    /**
     * 预算切片：剩余预算（budgetMax − 锚点价）按规则权重比例分配到各品类。
     * 预算或锚点价缺失、剩余预算非正时返回空 Map（不下发预算过滤）。
     */
    private Map<String, BigDecimal> splitBudget(BigDecimal budgetMax, BigDecimal anchorPrice,
                                                List<MarketingAgentProperties.CompanionRule> rules) {
        if (budgetMax == null || anchorPrice == null) {
            return Map.of();
        }
        BigDecimal remaining = budgetMax.subtract(anchorPrice);
        if (remaining.signum() <= 0) {
            return Map.of();
        }
        double totalWeight = rules.stream().mapToDouble(MarketingAgentProperties.CompanionRule::getWeight).sum();
        if (totalWeight <= 0) {
            return Map.of();
        }
        Map<String, BigDecimal> split = new LinkedHashMap<>();
        for (MarketingAgentProperties.CompanionRule rule : rules) {
            if (!StringUtils.hasText(rule.getCategoryCode())) {
                continue;
            }
            BigDecimal slice = remaining
                .multiply(BigDecimal.valueOf(rule.getWeight() / totalWeight))
                .setScale(0, RoundingMode.DOWN);
            if (slice.signum() > 0) {
                split.put(rule.getCategoryCode().trim(), slice);
            }
        }
        return split;
    }
}
