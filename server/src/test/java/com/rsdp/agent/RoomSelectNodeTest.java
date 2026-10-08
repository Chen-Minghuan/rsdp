package com.rsdp.agent;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.graph.nodes.RoomSelectNode;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RoomSelectNode} 单元测试（标签匹配 / 类型别名 / 未命中 / 无户型降级守卫）。
 */
class RoomSelectNodeTest {

    private AgentEventBus eventBus;
    private AgentRunRecorder runRecorder;
    private AgentSessionMapper sessionMapper;
    private RoomSelectNode node;

    @BeforeEach
    void setUp() {
        eventBus = mock(AgentEventBus.class);
        runRecorder = mock(AgentRunRecorder.class);
        sessionMapper = mock(AgentSessionMapper.class);
        node = new RoomSelectNode(eventBus, runRecorder, sessionMapper);
    }

    @AfterEach
    void tearDown() {
        AgentRunContext.remove("RUN-1");
    }

    private AgentRunContext registerCtx(FloorPlanContext context, String targetRoomName) {
        AgentRunContext ctx = new AgentRunContext("SES-1", "RUN-1", "user-1", "看看主卧");
        ctx.setFloorPlanContext(context);
        ctx.setTargetRoomName(targetRoomName);
        AgentRunContext.register(ctx);
        return ctx;
    }

    private FloorPlanContext sampleContext() {
        return new FloorPlanContext("FPA-1", List.of(
            new FloorPlanRoomFact("ROOM-1", "客厅", "LIVING_ROOM", 5200, 4300, new BigDecimal("22.36")),
            new FloorPlanRoomFact("ROOM-2", "主卧", "MASTER_BEDROOM", 4200, 3800, new BigDecimal("15.96")),
            new FloorPlanRoomFact("ROOM-3", "次卧", "BEDROOM", 3500, 3200, new BigDecimal("11.20"))
        ), null);
    }

    private OverAllState state() {
        return new OverAllState(Map.of(AgentStateKeys.STATE_RUN_ID, "RUN-1"));
    }

    @Test
    void labelMatchShouldSwitchActiveRoom() throws Exception {
        AgentRunContext ctx = registerCtx(sampleContext(), "主卧");

        node.apply(state());

        ArgumentCaptor<UpdateWrapper> captor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(sessionMapper).update(any(), captor.capture());
        assertThat(ctx.assistantText()).contains("主卧");
        assertThat(ctx.assistantText()).contains("4200×3800");
    }

    @Test
    void labelContainsShouldMatch() throws Exception {
        // 标签 "主卧" 与目标 "主卧室" 互相包含即命中
        AgentRunContext ctx = registerCtx(sampleContext(), "主卧室");

        node.apply(state());

        verify(sessionMapper).update(any(), any(UpdateWrapper.class));
        assertThat(ctx.assistantText()).contains("主卧");
    }

    @Test
    void unknownRoomShouldListAvailableRooms() throws Exception {
        AgentRunContext ctx = registerCtx(sampleContext(), "健身房");

        node.apply(state());

        verify(sessionMapper, never()).update(any(), any(UpdateWrapper.class));
        assertThat(ctx.assistantText()).contains("没有找到这个空间");
        assertThat(ctx.assistantText()).contains("客厅").contains("主卧").contains("次卧");
    }

    @Test
    void blankTargetShouldListAvailableRooms() throws Exception {
        AgentRunContext ctx = registerCtx(sampleContext(), null);

        node.apply(state());

        verify(sessionMapper, never()).update(any(), any(UpdateWrapper.class));
        assertThat(ctx.assistantText()).contains("没有找到这个空间");
    }

    @Test
    void noFloorPlanShouldGuideVerbalFallback() throws Exception {
        AgentRunContext ctx = registerCtx(null, "主卧");

        node.apply(state());

        verify(sessionMapper, never()).update(any(), any(UpdateWrapper.class));
        assertThat(ctx.assistantText()).contains("还没有绑定户型");
        verify(eventBus).emitToken(eq("RUN-1"), anyString());
    }
}
