package com.rsdp.agent;

import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.domain.ProductSearchCriteria;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.domain.ProductSearchResult;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.skill.SkillContext;
import com.rsdp.agent.skill.SkillResult;
import com.rsdp.agent.skill.livingroom.LivingRoomMatchingSkill;
import com.rsdp.agent.tool.AgentReadTools;
import com.rsdp.entity.RspuMaster;
import com.rsdp.entity.RspuRelation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link LivingRoomMatchingSkill} 单元测试（确定性搭配流程守卫）。
 */
class LivingRoomMatchingSkillTest {

    private AgentReadTools tools;
    private LivingRoomMatchingSkill skill;

    @BeforeEach
    void setUp() {
        tools = mock(AgentReadTools.class);
        skill = new LivingRoomMatchingSkill(new MarketingAgentProperties());
    }

    private AgentConfirmedItem confirmed(String itemId, String rspuId) {
        AgentConfirmedItem item = new AgentConfirmedItem();
        item.setItemId(itemId);
        item.setRspuId(rspuId);
        item.setStatus("confirmed");
        return item;
    }

    private RspuMaster product(String rspuId, String categoryCode, BigDecimal retailPrice) {
        RspuMaster product = new RspuMaster();
        product.setRspuId(rspuId);
        product.setCategoryCode(categoryCode);
        product.setRetailPrice(retailPrice);
        return product;
    }

    private ProductSearchItem item(String rspuId) {
        ProductSearchItem item = new ProductSearchItem();
        item.setRspuId(rspuId);
        item.setProductName("产品-" + rspuId);
        return item;
    }

    private ProductSearchResult resultOf(List<ProductSearchItem> items) {
        ProductSearchResult result = new ProductSearchResult();
        result.setItems(items);
        result.setTotalMatched(items.size());
        return result;
    }

    private SkillContext ctx(List<AgentConfirmedItem> confirmed, RequirementConstraints constraints) {
        return new SkillContext("SES-1", "RUN-1", constraints, confirmed, Map.of(), tools);
    }

    @Test
    void noConfirmedItemShouldReturnEmpty() {
        SkillResult result = skill.execute(ctx(List.of(), new RequirementConstraints()));

        assertThat(result.groups()).isEmpty();
        assertThat(result.trace()).containsEntry("reason", "no_confirmed_item");
    }

    @Test
    void anchorCategoryWithoutRuleShouldReturnEmpty() {
        when(tools.findProducts(anyCollection()))
            .thenReturn(Map.of("RSPU-BD-1", product("RSPU-BD-1", "BD", new BigDecimal("8000"))));

        SkillResult result = skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-BD-1")), new RequirementConstraints()));

        assertThat(result.groups()).isEmpty();
        assertThat(result.trace()).containsEntry("reason", "no_rule_for_category");
    }

    @Test
    void sofaAnchorShouldSearchCompanionCategoriesWithInheritedConstraints() {
        RspuMaster anchor = product("RSPU-SF-1", "SF", new BigDecimal("10000"));
        when(tools.findProducts(anyCollection())).thenReturn(Map.of("RSPU-SF-1", anchor));
        when(tools.listExcludedRspuIds("RSPU-SF-1")).thenReturn(Set.of());
        when(tools.listCompanionRelations("RSPU-SF-1")).thenReturn(List.of());
        when(tools.searchProducts(any(ProductSearchCriteria.class))).thenAnswer(invocation -> {
            ProductSearchCriteria criteria = invocation.getArgument(0);
            return resultOf(List.of(item("HIT-" + criteria.getCategoryCode())));
        });

        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setStyle("中古风");
        constraints.setColor("焦糖棕");
        constraints.setBudgetMax(new BigDecimal("20000"));

        SkillResult result = skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-SF-1")), constraints));

        // 默认规则 SF → TB/FC/FS 三组
        assertThat(result.groups()).extracting(SkillResult.CompanionGroup::categoryCode)
            .containsExactly("TB", "FC", "FS");
        // 约束继承 + 预算切片（剩余 10000 按 0.5/0.3/0.2 分配）
        List<ProductSearchCriteria> captured = new ArrayList<>();
        verify(tools, times(3)).searchProducts(org.mockito.ArgumentMatchers.argThat(c -> {
            captured.add(c);
            return true;
        }));
        assertThat(captured).allSatisfy(c -> {
            assertThat(c.getStyle()).isEqualTo("中古风");
            assertThat(c.getColor()).isEqualTo("焦糖棕");
        });
        assertThat(captured.get(0).getBudgetMax()).isEqualByComparingTo("5000");
        assertThat(captured.get(1).getBudgetMax()).isEqualByComparingTo("3000");
        assertThat(captured.get(2).getBudgetMax()).isEqualByComparingTo("2000");
    }

    @Test
    void excludedAndConfirmedProductsShouldBeFiltered() {
        RspuMaster anchor = product("RSPU-SF-1", "SF", null);
        when(tools.findProducts(anyCollection())).thenReturn(Map.of("RSPU-SF-1", anchor));
        when(tools.listExcludedRspuIds("RSPU-SF-1")).thenReturn(Set.of("RSPU-TB-EXCLUDED"));
        when(tools.listCompanionRelations("RSPU-SF-1")).thenReturn(List.of());
        when(tools.searchProducts(any(ProductSearchCriteria.class))).thenAnswer(invocation -> {
            ProductSearchCriteria criteria = invocation.getArgument(0);
            if ("TB".equals(criteria.getCategoryCode())) {
                return resultOf(List.of(item("RSPU-TB-EXCLUDED"), item("RSPU-TB-2"), item("RSPU-SF-1")));
            }
            return resultOf(List.of());
        });

        SkillResult result = skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-SF-1")), new RequirementConstraints()));

        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().get(0).items()).extracting(ProductSearchItem::getRspuId)
            .containsExactly("RSPU-TB-2");
    }

    @Test
    void pinnedRelationProductsShouldLeadTheGroup() {
        RspuMaster anchor = product("RSPU-SF-1", "SF", null);
        RspuRelation relation = new RspuRelation();
        relation.setRelatedRspuId("RSPU-TB-PINNED");
        when(tools.findProducts(anyCollection())).thenAnswer(invocation -> {
            List<String> ids = new ArrayList<>((java.util.Collection<String>) invocation.getArgument(0));
            Map<String, RspuMaster> map = new java.util.HashMap<>();
            if (ids.contains("RSPU-SF-1")) {
                map.put("RSPU-SF-1", anchor);
            }
            if (ids.contains("RSPU-TB-PINNED")) {
                map.put("RSPU-TB-PINNED", product("RSPU-TB-PINNED", "TB", null));
            }
            return map;
        });
        when(tools.listExcludedRspuIds("RSPU-SF-1")).thenReturn(Set.of());
        when(tools.listCompanionRelations("RSPU-SF-1")).thenReturn(List.of(relation));
        when(tools.findProductItems(anyCollection())).thenReturn(List.of(item("RSPU-TB-PINNED")));
        when(tools.searchProducts(any(ProductSearchCriteria.class))).thenAnswer(invocation -> {
            ProductSearchCriteria criteria = invocation.getArgument(0);
            if ("TB".equals(criteria.getCategoryCode())) {
                // 检索结果也含 pinned 产品：不应重复出现
                return resultOf(List.of(item("RSPU-TB-PINNED"), item("RSPU-TB-9")));
            }
            return resultOf(List.of());
        });

        SkillResult result = skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-SF-1")), new RequirementConstraints()));

        assertThat(result.groups()).hasSize(1);
        SkillResult.CompanionGroup group = result.groups().get(0);
        assertThat(group.items()).extracting(ProductSearchItem::getRspuId)
            .containsExactly("RSPU-TB-PINNED", "RSPU-TB-9");
        assertThat(group.pinnedRspuIds()).containsExactly("RSPU-TB-PINNED");
    }

    @Test
    void searchFailureShouldSkipCategoryWithoutBreakingOthers() {
        RspuMaster anchor = product("RSPU-SF-1", "SF", null);
        when(tools.findProducts(anyCollection())).thenReturn(Map.of("RSPU-SF-1", anchor));
        when(tools.listExcludedRspuIds("RSPU-SF-1")).thenReturn(Set.of());
        when(tools.listCompanionRelations("RSPU-SF-1")).thenReturn(List.of());
        when(tools.searchProducts(any(ProductSearchCriteria.class))).thenAnswer(invocation -> {
            ProductSearchCriteria criteria = invocation.getArgument(0);
            if ("TB".equals(criteria.getCategoryCode())) {
                throw new RuntimeException("DB 异常");
            }
            if ("FS".equals(criteria.getCategoryCode())) {
                return resultOf(List.of(item("RSPU-FS-1")));
            }
            return resultOf(List.of());
        });

        SkillResult result = skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-SF-1")), new RequirementConstraints()));

        assertThat(result.groups()).hasSize(1);
        assertThat(result.groups().get(0).categoryCode()).isEqualTo("FS");
    }

    @Test
    void missingBudgetOrAnchorPriceShouldSkipBudgetFilter() {
        RspuMaster anchor = product("RSPU-SF-1", "SF", null);
        when(tools.findProducts(anyCollection())).thenReturn(Map.of("RSPU-SF-1", anchor));
        when(tools.listExcludedRspuIds("RSPU-SF-1")).thenReturn(Set.of());
        when(tools.listCompanionRelations("RSPU-SF-1")).thenReturn(List.of());
        when(tools.searchProducts(any(ProductSearchCriteria.class))).thenReturn(resultOf(List.of()));

        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setBudgetMax(new BigDecimal("20000"));

        skill.execute(ctx(List.of(confirmed("CFI-1", "RSPU-SF-1")), constraints));

        verify(tools, times(3)).searchProducts(org.mockito.ArgumentMatchers.argThat(
            c -> c.getBudgetMax() == null));
    }

    @Test
    void metadataShouldFollowSkillConvention() {
        assertThat(skill.metadata().id()).isEqualTo("living-room-matching");
        assertThat(skill.metadata().version()).isEqualTo("1.0.0");
        assertThat(skill.metadata().riskLevel()).isEqualTo("LOW");
        assertThat(skill.metadata().allowedTools())
            .containsExactlyInAnyOrder("search_products", "list_relations", "find_products");
        assertThat(skill.metadata().requiredPermissions()).containsExactly("agent:use");
        assertThat(skill.metadata().maxSteps()).isEqualTo(4);
    }
}
