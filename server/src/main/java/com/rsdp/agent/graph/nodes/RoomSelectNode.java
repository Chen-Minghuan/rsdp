package com.rsdp.agent.graph.nodes;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 房间切换节点：SWITCH_ROOM 意图 → 匹配目标房间 → 更新会话 active_room_id。
 *
 * <p>匹配规则（确定性，不调 LLM）：先按标签原文包含匹配，再按空间类型码别名匹配；
 * 未命中时回复可用房间列表。切换成功后本轮即结束，下一轮检索自动带新房间约束。</p>
 */
@Slf4j
@Component
public class RoomSelectNode extends AbstractAgentNode {

    /** 常见叫法 → 匹配关键词（命中标签或类型码即算匹配）。 */
    private static final Map<String, String> ROOM_ALIASES = Map.ofEntries(
        Map.entry("客厅", "LIVING_ROOM"),
        Map.entry("起居室", "LIVING_ROOM"),
        Map.entry("餐厅", "DINING_ROOM"),
        Map.entry("主卧", "MASTER_BEDROOM"),
        Map.entry("次卧", "BEDROOM"),
        Map.entry("卧室", "BEDROOM"),
        Map.entry("书房", "STUDY_ROOM"),
        Map.entry("儿童房", "KIDS_ROOM"),
        Map.entry("厨房", "KITCHEN"),
        Map.entry("卫生间", "BATHROOM"),
        Map.entry("阳台", "BALCONY")
    );

    private final AgentSessionMapper sessionMapper;

    public RoomSelectNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                          AgentSessionMapper sessionMapper) {
        super(eventBus, runRecorder);
        this.sessionMapper = sessionMapper;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_ROOM_SELECT;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        FloorPlanContext context = ctx.getFloorPlanContext();
        if (context == null) {
            String text = "当前会话还没有绑定户型，直接告诉我您的空间尺寸也可以继续选品。";
            eventBus.emitToken(ctx.getRunId(), text);
            ctx.appendAssistantText(text);
            return Map.of();
        }

        FloorPlanRoomFact target = matchRoom(context, ctx.getTargetRoomName());
        if (target == null) {
            String roomList = context.rooms().stream()
                .map(RoomSelectNode::roomBrief)
                .collect(Collectors.joining("、"));
            String text = "没有找到这个空间。当前户型有：" + roomList + "，想先看哪一个？";
            eventBus.emitToken(ctx.getRunId(), text);
            ctx.appendAssistantText(text);
            return Map.of();
        }

        sessionMapper.update(null, new UpdateWrapper<com.rsdp.agent.entity.AgentSession>()
            .eq("session_id", ctx.getSessionId())
            .set("active_room_id", target.roomId())
            .set("updated_at", LocalDateTime.now()));

        String sizeText = target.widthMm() != null && target.depthMm() != null
            ? "（" + target.widthMm() + "×" + target.depthMm() + "mm"
              + (target.areaM2() != null ? "，约 " + target.areaM2() + "㎡" : "") + "）"
            : "";
        String text = "好的，现在为" + target.displayName() + sizeText + "选品。"
            + "这个空间的尺寸我会作为硬约束，告诉我想配什么产品吧。";
        eventBus.emitToken(ctx.getRunId(), text);
        ctx.appendAssistantText(text);
        log.info("房间已切换，runId={}, sessionId={}, activeRoom={}({})",
            ctx.getRunId(), ctx.getSessionId(), target.displayName(), target.roomId());
        return Map.of();
    }

    /** 匹配目标房间：标签包含优先，类型码别名兜底；目标名为空时返回 null。 */
    private FloorPlanRoomFact matchRoom(FloorPlanContext context, String targetName) {
        if (!StringUtils.hasText(targetName)) {
            return null;
        }
        String name = targetName.trim();
        // 1. 标签原文包含（"主卧" 命中 "主卧"/"主卧室"）
        for (FloorPlanRoomFact room : context.rooms()) {
            if (room.label() != null && (room.label().contains(name) || name.contains(room.label()))) {
                return room;
            }
        }
        // 2. 类型码别名（别名指向的类型在该户型中存在时）
        String aliasType = ROOM_ALIASES.get(name);
        if (aliasType != null) {
            for (FloorPlanRoomFact room : context.rooms()) {
                if (aliasType.equals(room.roomType())) {
                    return room;
                }
            }
        }
        return null;
    }

    /** 房间简介（列表展示用）：名称 + 尺寸。 */
    private static String roomBrief(FloorPlanRoomFact room) {
        String size = room.widthMm() != null && room.depthMm() != null
            ? " " + room.widthMm() + "×" + room.depthMm() + "mm"
            : "";
        return room.displayName() + size;
    }
}
