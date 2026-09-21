package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.graph.AgentRouter;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.graph.ConstraintCompleteness;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.service.AgentRunContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link AgentRouter} 单元测试（意图 → 路由矩阵守卫）。
 */
class AgentRouterTest {

    private AgentRouter router;
    private MarketingAgentProperties properties;
    private AgentConfirmedItemMapper confirmedItemMapper;

    @BeforeEach
    void setUp() {
        properties = new MarketingAgentProperties();
        confirmedItemMapper = mock(AgentConfirmedItemMapper.class);
        // 默认 maxFollowupRounds = 3
        router = new AgentRouter(new ConstraintCompleteness(), properties, confirmedItemMapper);
    }

    private AgentRunContext ctx(RequirementConstraints constraints, int followupCount) {
        AgentRunContext ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "消息");
        ctx.setConstraints(constraints);
        ctx.setFollowupCount(followupCount);
        return ctx;
    }

    private RequirementConstraints completeConstraints() {
        RequirementConstraints constraints = new RequirementConstraints();
        constraints.setCategoryCode("沙发");
        constraints.setBudgetMax(new BigDecimal("20000"));
        return constraints;
    }

    @Test
    void chitchatIntentShouldRouteToChitchat() {
        assertThat(router.route(AgentStateKeys.INTENT_CHITCHAT, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_CHITCHAT);
    }

    @Test
    void nullOrBlankIntentShouldRouteToChitchat() {
        assertThat(router.route(null, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_CHITCHAT);
        assertThat(router.route("  ", ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_CHITCHAT);
    }

    @Test
    void confirmItemShouldRouteToConfirmHint() {
        // 即使约束不齐备，CONFIRM_ITEM 也直接引导按钮确认
        assertThat(router.route(AgentStateKeys.INTENT_CONFIRM_ITEM, ctx(new RequirementConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_CONFIRM_HINT);
    }

    @Test
    void newRequirementWithCompleteConstraintsShouldRouteToSearch() {
        assertThat(router.route(AgentStateKeys.INTENT_NEW_REQUIREMENT, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_SEARCH);
    }

    @Test
    void refineWithIncompleteConstraintsShouldRouteToFollowup() {
        assertThat(router.route(AgentStateKeys.INTENT_REFINE, ctx(new RequirementConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_FOLLOWUP);
    }

    @Test
    void confirmRequirementWithIncompleteConstraintsShouldFollowupFirst() {
        // CONFIRM_REQUIREMENT 也要先补齐关键约束
        assertThat(router.route(AgentStateKeys.INTENT_CONFIRM_REQUIREMENT, ctx(new RequirementConstraints(), 1)))
            .isEqualTo(AgentStateKeys.ROUTE_FOLLOWUP);
    }

    @Test
    void incompleteConstraintsWithFollowupRoundsExhaustedShouldRouteToSearch() {
        // followupCount=3 达到上限 maxFollowupRounds=3 → 直接检索
        assertThat(router.route(AgentStateKeys.INTENT_REFINE,
                ctx(new RequirementConstraints(), properties.getMaxFollowupRounds())))
            .isEqualTo(AgentStateKeys.ROUTE_SEARCH);
    }

    @Test
    void confirmRequirementWithCompleteConstraintsShouldRouteToSearch() {
        assertThat(router.route(AgentStateKeys.INTENT_CONFIRM_REQUIREMENT, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_SEARCH);
    }

    @Test
    void feedbackModifyWithIncompleteConstraintsShouldRouteToFollowup() {
        RequirementConstraints partial = new RequirementConstraints();
        partial.setCategoryCode("沙发");

        assertThat(router.route(AgentStateKeys.INTENT_FEEDBACK_MODIFY, ctx(partial, 0)))
            .isEqualTo(AgentStateKeys.ROUTE_FOLLOWUP);
    }

    @Test
    void matchCompanionWithConfirmedItemsShouldRouteToCompanion() {
        when(confirmedItemMapper.selectCount(any(QueryWrapper.class))).thenReturn(1L);

        assertThat(router.route(AgentStateKeys.INTENT_MATCH_COMPANION, ctx(new RequirementConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_COMPANION);
    }

    @Test
    void matchCompanionWithoutConfirmedItemsShouldRouteToActionHint() {
        when(confirmedItemMapper.selectCount(any(QueryWrapper.class))).thenReturn(0L);

        assertThat(router.route(AgentStateKeys.INTENT_MATCH_COMPANION, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_ACTION_HINT);
    }

    @Test
    void requestQuoteShouldRouteToActionHint() {
        // 写操作不进图：即使有齐备约束也只引导点面板按钮
        assertThat(router.route(AgentStateKeys.INTENT_REQUEST_QUOTE, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_ACTION_HINT);
    }

    @Test
    void exportSchemeShouldRouteToActionHint() {
        assertThat(router.route(AgentStateKeys.INTENT_EXPORT_SCHEME, ctx(completeConstraints(), 0)))
            .isEqualTo(AgentStateKeys.ROUTE_ACTION_HINT);
    }
}
