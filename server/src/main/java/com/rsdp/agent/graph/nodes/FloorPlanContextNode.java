package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.agent.service.FloorPlanContextService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 户型上下文节点（不调用 LLM）：加载会话绑定户型的空间事实进运行上下文。
 *
 * <p>每个 run 的最前节点。未绑定户型 / 户型未确认 / 无房间时上下文为 null，
 * 后续节点全部退化为纯口头描述路径（降级铁律）。</p>
 */
@Slf4j
@Component
public class FloorPlanContextNode extends AbstractAgentNode {

    private final AgentSessionMapper sessionMapper;
    private final FloorPlanContextService floorPlanContextService;

    public FloorPlanContextNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                                AgentSessionMapper sessionMapper,
                                FloorPlanContextService floorPlanContextService) {
        super(eventBus, runRecorder);
        this.sessionMapper = sessionMapper;
        this.floorPlanContextService = floorPlanContextService;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_FLOOR_PLAN_CONTEXT;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        AgentSession session = sessionMapper.selectById(ctx.getSessionId());
        if (session == null) {
            ctx.setFloorPlanContext(null);
            return Map.of();
        }
        FloorPlanContext context = floorPlanContextService.loadContext(
            session.getFloorPlanAnalysisId(), session.getActiveRoomId());
        ctx.setFloorPlanContext(context);
        if (context != null) {
            log.info("户型上下文已加载，runId={}, analysisId={}, rooms={}, activeRoom={}",
                ctx.getRunId(), context.analysisId(), context.rooms().size(),
                context.activeRoom() != null ? context.activeRoom().roomId() : "-");
        }
        return Map.of();
    }
}
