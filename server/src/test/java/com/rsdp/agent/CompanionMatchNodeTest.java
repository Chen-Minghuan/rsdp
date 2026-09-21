package com.rsdp.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.domain.ProductSearchItem;
import com.rsdp.agent.entity.AgentConfirmedItem;
import com.rsdp.agent.entity.AgentRecommendBatch;
import com.rsdp.agent.entity.AgentRecommendItem;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.graph.nodes.CompanionMatchNode;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentRecommendBatchMapper;
import com.rsdp.agent.mapper.AgentRecommendItemMapper;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.agent.service.RecommendReasoner;
import com.rsdp.agent.skill.AgentSkill;
import com.rsdp.agent.skill.SkillContext;
import com.rsdp.agent.skill.SkillMetadata;
import com.rsdp.agent.skill.SkillRegistry;
import com.rsdp.agent.skill.SkillResult;
import com.rsdp.agent.skill.livingroom.LivingRoomMatchingSkill;
import com.rsdp.agent.tool.AgentReadTools;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CompanionMatchNode} 单元测试（Skill 编排与批次落库守卫）。
 */
class CompanionMatchNodeTest {

    private AgentEventBus eventBus;
    private AgentRunRecorder runRecorder;
    private AgentConfirmedItemMapper confirmedItemMapper;
    private AgentReadTools readTools;
    private RecommendReasoner reasoner;
    private AgentChatService chatService;
    private AgentRecommendBatchMapper batchMapper;
    private AgentRecommendItemMapper itemMapper;
    private AgentMessageStore messageStore;
    private AgentRunContext ctx;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        eventBus = mock(AgentEventBus.class);
        runRecorder = mock(AgentRunRecorder.class);
        confirmedItemMapper = mock(AgentConfirmedItemMapper.class);
        readTools = mock(AgentReadTools.class);
        reasoner = mock(RecommendReasoner.class);
        chatService = mock(AgentChatService.class);
        batchMapper = mock(AgentRecommendBatchMapper.class);
        itemMapper = mock(AgentRecommendItemMapper.class);
        messageStore = mock(AgentMessageStore.class);
        ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "帮我配个茶几");
        AgentRunContext.register(ctx);
    }

    @AfterEach
    void tearDown() {
        AgentRunContext.remove("RUN-1");
    }

    private OverAllState state() {
        OverAllState state = mock(OverAllState.class);
        when(state.value(eq(AgentStateKeys.STATE_RUN_ID), anyString())).thenReturn("RUN-1");
        return state;
    }

    private AgentConfirmedItem confirmed(String rspuId) {
        AgentConfirmedItem item = new AgentConfirmedItem();
        item.setItemId("CFI-1");
        item.setRspuId(rspuId);
        item.setStatus("confirmed");
        return item;
    }

    private ProductSearchItem productItem(String rspuId) {
        ProductSearchItem item = new ProductSearchItem();
        item.setRspuId(rspuId);
        item.setProductName("茶几-" + rspuId);
        item.setRetailPrice(new BigDecimal("2000"));
        return item;
    }

    /** 返回固定一组 TB 候选的 stub Skill。 */
    private AgentSkill stubSkill() {
        return new AgentSkill() {
            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(LivingRoomMatchingSkill.SKILL_ID,
                    LivingRoomMatchingSkill.SKILL_VERSION, "测试", "LOW",
                    Set.of("search_products"), Set.of("agent:use"), Map.of(), Map.of(), 4);
            }

            @Override
            public SkillResult execute(SkillContext context) {
                SkillResult.CompanionGroup group = new SkillResult.CompanionGroup(
                    "TB", "茶几", List.of(productItem("RSPU-TB-1"), productItem("RSPU-TB-2")), List.of());
                return new SkillResult(List.of(group), Map.of("anchorRspuId", "RSPU-SF-1"));
            }
        };
    }

    private CompanionMatchNode node(AgentSkill skill) {
        return new CompanionMatchNode(eventBus, runRecorder, confirmedItemMapper,
            new SkillRegistry(List.of(skill)), readTools, reasoner, chatService,
            batchMapper, itemMapper, messageStore, objectMapper);
    }

    @Test
    void noConfirmedItemsShouldEmitAnchorHintWithoutExecutingSkill() throws Exception {
        when(confirmedItemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        node(stubSkill()).apply(state());

        verify(eventBus).emitToken(eq("RUN-1"), org.mockito.ArgumentMatchers.contains("还没有已确认的主体产品"));
        assertThat(ctx.assistantText()).contains("还没有已确认的主体产品");
    }

    @Test
    void shouldPersistCompanionBatchWithSkillTraceAndGroupTag() throws Exception {
        when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(confirmed("RSPU-SF-1")));
        when(reasoner.generateReasons(any(), any(), any())).thenReturn(List.of());
        when(chatService.streamCollect(anyString(), anyString(), any())).thenReturn("引导文案");

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority("agent:use")).thenReturn(true);

            node(stubSkill()).apply(state());
        }

        ArgumentCaptor<AgentRecommendBatch> batchCaptor = ArgumentCaptor.forClass(AgentRecommendBatch.class);
        verify(batchMapper).insert(batchCaptor.capture());
        AgentRecommendBatch batch = batchCaptor.getValue();
        assertThat(batch.getSkillId()).isEqualTo("living-room-matching");
        assertThat(batch.getSkillVersion()).isEqualTo("1.0.0");
        assertThat(batch.getBatchType()).isEqualTo("companion");
        assertThat(batch.getQueryCriteria()).contains("RSPU-SF-1");

        ArgumentCaptor<AgentRecommendItem> itemCaptor = ArgumentCaptor.forClass(AgentRecommendItem.class);
        verify(itemMapper, org.mockito.Mockito.times(2)).insert(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues()).allSatisfy(item -> {
            assertThat(item.getGroupTag()).isEqualTo("TB");
            assertThat(item.getSnapshot()).contains("茶几-");
        });
        assertThat(itemCaptor.getAllValues().get(0).getRank()).isEqualTo(1);
        assertThat(itemCaptor.getAllValues().get(1).getRank()).isEqualTo(2);

        verify(messageStore).persistAssistantMessage(eq("SES-1"), eq("RUN-1"), eq("cards"), anyString(),
            org.mockito.ArgumentMatchers.contains("companion"));
        verify(eventBus).emitCards(eq("RUN-1"), eq(batch.getBatchId()), any());
    }

    @Test
    void missingRequiredPermissionShouldThrow403() {
        when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(confirmed("RSPU-SF-1")));

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority("agent:use")).thenReturn(false);

            assertThatThrownBy(() -> node(stubSkill()).apply(state()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("agent:use");
        }
    }

    @Test
    void emptySkillResultShouldEmitNoMatchText() throws Exception {
        when(confirmedItemMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(confirmed("RSPU-SF-1")));
        AgentSkill emptySkill = new AgentSkill() {
            @Override
            public SkillMetadata metadata() {
                return new SkillMetadata(LivingRoomMatchingSkill.SKILL_ID,
                    LivingRoomMatchingSkill.SKILL_VERSION, "测试", "LOW",
                    Set.of(), Set.of("agent:use"), Map.of(), Map.of(), 4);
            }

            @Override
            public SkillResult execute(SkillContext context) {
                return SkillResult.empty(Map.of("reason", "no_rule_for_category"));
            }
        };

        try (var mocked = mockStatic(SecurityOperatorContext.class)) {
            mocked.when(() -> SecurityOperatorContext.hasAuthority("agent:use")).thenReturn(true);

            node(emptySkill).apply(state());
        }

        verify(eventBus).emitToken(eq("RUN-1"), org.mockito.ArgumentMatchers.contains("合适的配套产品"));
    }
}
