package com.rsdp.agent.graph.nodes;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.rsdp.agent.dto.LlmRequirementExtraction;
import com.rsdp.agent.dto.RequirementProfileResponse;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentRequirementVersion;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentRequirementVersionMapper;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.agent.patch.PatchOperation;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.agent.patch.RequirementPatch;
import com.rsdp.agent.patch.RequirementPatchReducer;
import com.rsdp.agent.patch.RequirementScope;
import com.rsdp.agent.service.AgentChatService;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentRunContext;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 需求理解节点：LLM 抽取意图 + operations，Java Reducer 应用 patch，
 * 有生效操作则落新需求版本并经 SSE 全量刷新档案。
 *
 * <p>铁律：模型只输出 operations，档案修改由 {@link RequirementPatchReducer} 完成。</p>
 */
@Slf4j
@Component
public class RequirementPatchNode extends AbstractAgentNode {

    /** 需求抽取 system prompt（严格 JSON、evidence 必须为用户原话）。 */
    private static final String SYSTEM_PROMPT = """
        你是家居选品需求理解助手。从对话中识别用户意图并抽取需求约束变更，只输出严格 JSON，不要 markdown 代码块，不要任何解释文字：
        {
          "intent": "NEW_REQUIREMENT | REFINE | FEEDBACK_MODIFY | CONFIRM_REQUIREMENT | CONFIRM_ITEM | MATCH_COMPANION | REQUEST_QUOTE | EXPORT_SCHEME | SWITCH_ROOM | CHITCHAT",
          "operations": [
            {"field": "字段名", "operation": "set | clear", "value": "值", "evidence": "用户原话片段"}
          ],
          "targetRoom": "目标房间名（仅 intent=SWITCH_ROOM 时输出，如 主卧/客厅）"
        }
        规则：
        - intent 含义：NEW_REQUIREMENT=首次提出选品需求；REFINE=补充或修改需求；FEEDBACK_MODIFY=针对推荐结果提出调整；CONFIRM_REQUIREMENT=明确表示需求就这些、可以开始推荐（如"就按这个找""可以推荐了"）；CONFIRM_ITEM=明确表示选定某一款产品（如"就要第一款"）；MATCH_COMPANION=想要围绕已确认产品搭配配套产品（如"帮我配个茶几""搭配一下客厅"）；REQUEST_QUOTE=要求报价或问整体价格（如"帮我报价""这些一共多少钱"）；EXPORT_SCHEME=明确表示要生成方案或下单（如"生成方案""我要下单"）；SWITCH_ROOM=表示要换到另一个空间继续选品（如"客厅可以了，看看主卧""再配一下餐厅"），此时 operations 输出空数组；CHITCHAT=与选品无关的闲聊
        - operations 只包含用户明确提到的字段，不要臆测；无约束变更时输出空数组
        - field 仅限：categoryCode, categoryName, style, material, color, budgetMax, maxWidthMm, minWidthMm, sofaForm, areaM2, note
        - value 一律用字符串；budgetMax 单位为元（"两万"→"20000"）；maxWidthMm/minWidthMm 单位为毫米（"2.4米"→"2400"）；areaM2 单位为平方米
        - evidence 必须是用户原话中的片段，不得改写
        - 用户要求清除某项时用 clear（value/evidence 可空）
        - categoryCode 用通用品类词（如 沙发/茶几/床/餐桌/电视柜），categoryName 用对应中文名
        """;

    /** 带入上下文的最近消息条数。 */
    private static final int HISTORY_LIMIT = 12;

    private final AgentChatService chatService;
    private final RequirementPatchReducer reducer;
    private final AgentRequirementVersionMapper requirementVersionMapper;
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageStore messageStore;
    private final com.rsdp.agent.service.AgentSessionService sessionService;
    private final ObjectMapper objectMapper;

    public RequirementPatchNode(AgentEventBus eventBus, AgentRunRecorder runRecorder,
                                AgentChatService chatService, RequirementPatchReducer reducer,
                                AgentRequirementVersionMapper requirementVersionMapper,
                                AgentSessionMapper sessionMapper, AgentMessageStore messageStore,
                                com.rsdp.agent.service.AgentSessionService sessionService,
                                ObjectMapper objectMapper) {
        super(eventBus, runRecorder);
        this.chatService = chatService;
        this.reducer = reducer;
        this.requirementVersionMapper = requirementVersionMapper;
        this.sessionMapper = sessionMapper;
        this.messageStore = messageStore;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected String nodeName() {
        return AgentStateKeys.NODE_REQUIREMENT_PATCH;
    }

    @Override
    protected Map<String, Object> doApply(OverAllState state, AgentRunContext ctx) {
        String intent = AgentStateKeys.INTENT_CHITCHAT;
        try {
            String raw = chatService.call(SYSTEM_PROMPT, buildUserPrompt(ctx));
            LlmRequirementExtraction extraction = chatService.parseJson(raw, LlmRequirementExtraction.class);
            if (extraction != null) {
                if (extraction.getIntent() != null && !extraction.getIntent().isBlank()) {
                    intent = extraction.getIntent().trim();
                }
                if (AgentStateKeys.INTENT_SWITCH_ROOM.equals(intent)) {
                    // 房间切换：只记录目标房间名，不应用需求 patch（由 RoomSelectNode 处理）
                    ctx.setTargetRoomName(extraction.getTargetRoom());
                } else {
                    applyPatch(ctx, extraction);
                }
            } else {
                log.warn("需求抽取 JSON 解析失败，按 CHITCHAT 降级，runId={}", ctx.getRunId());
            }
        } catch (Exception e) {
            // LLM 故障：按闲聊降级，由 ChitchatNode 给出兜底回复，不中断 run
            log.error("需求抽取 LLM 调用失败，按 CHITCHAT 降级，runId={}", ctx.getRunId(), e);
        }
        return Map.of(AgentStateKeys.STATE_INTENT, intent);
    }

    /** 应用 patch：有生效操作则落新版本并推送档案更新。 */
    private void applyPatch(AgentRunContext ctx, LlmRequirementExtraction extraction) throws Exception {
        List<PatchOperation> operations = extraction.getOperations();
        if (operations == null || operations.isEmpty()) {
            return;
        }

        // W2 作用域拆分：户型会话且有当前房间时，房间字段落房间作用域，全局字段落全局；
        // 无户型会话全部落全局（room_id NULL，历史行为不变）
        FloorPlanRoomFact activeRoom = ctx.getFloorPlanContext() != null
            ? ctx.getFloorPlanContext().activeRoom() : null;
        boolean roomScoped = activeRoom != null;
        List<PatchOperation> globalOps = new java.util.ArrayList<>();
        List<PatchOperation> roomOps = new java.util.ArrayList<>();
        for (PatchOperation operation : operations) {
            if (roomScoped && RequirementScope.isRoomField(operation != null ? operation.getField() : null)) {
                roomOps.add(operation);
            } else {
                globalOps.add(operation);
            }
        }

        String source = AgentStateKeys.INTENT_FEEDBACK_MODIFY.equals(extraction.getIntent())
            ? "feedback" : "extract";
        int nextVersionNo = ctx.getVersionNo();

        // 全局作用域（无户型会话 = 唯一作用域）
        RequirementConstraints globalUpdated = null;
        AgentRequirementVersion globalLatest = sessionService.latestGlobalVersion(ctx.getSessionId());
        if (!globalOps.isEmpty()) {
            RequirementConstraints globalBase = globalLatest != null
                ? RequirementConstraints.fromJson(globalLatest.getConstraints()) : new RequirementConstraints();
            RequirementPatchReducer.ApplyResult result = applyScope(ctx, globalBase, globalOps, null, source, ++nextVersionNo);
            if (result != null) {
                globalUpdated = result.getUpdated();
            } else {
                nextVersionNo--;
            }
        }

        // 房间作用域（仅户型会话）
        RequirementConstraints roomUpdated = null;
        AgentRequirementVersion roomLatest = roomScoped
            ? sessionService.latestRoomVersion(ctx.getSessionId(), activeRoom.roomId()) : null;
        if (roomScoped && !roomOps.isEmpty()) {
            RequirementConstraints roomBase = roomLatest != null
                ? RequirementConstraints.fromJson(roomLatest.getConstraints()) : new RequirementConstraints();
            RequirementPatchReducer.ApplyResult result = applyScope(ctx, roomBase, roomOps, activeRoom.roomId(), source, ++nextVersionNo);
            if (result != null) {
                roomUpdated = result.getUpdated();
            } else {
                nextVersionNo--;
            }
        }

        if (globalUpdated == null && roomUpdated == null) {
            return;
        }

        // 有效约束 = 全局为底 + 房间字段覆盖；未变更的作用域用现有快照
        RequirementConstraints globalEffective = globalUpdated != null ? globalUpdated
            : globalLatest != null ? RequirementConstraints.fromJson(globalLatest.getConstraints()) : null;
        RequirementConstraints roomEffective = roomUpdated != null ? roomUpdated
            : roomLatest != null ? RequirementConstraints.fromJson(roomLatest.getConstraints()) : null;
        RequirementConstraints effective = RequirementScope.mergeEffective(globalEffective, roomEffective);

        sessionMapper.update(null, new UpdateWrapper<AgentSession>()
            .eq("session_id", ctx.getSessionId())
            .set("current_version_no", nextVersionNo)
            .set("updated_at", LocalDateTime.now()));

        ctx.setConstraints(effective);
        ctx.setVersionNo(nextVersionNo);

        // SSE 全量刷新档案（有效视图 + 当前房间标识）+ 落 requirement 消息（前端刷新后从 GET 详情恢复）
        RequirementProfileResponse profile = new RequirementProfileResponse(nextVersionNo, effective, source);
        if (activeRoom != null) {
            profile.setRoomId(activeRoom.roomId());
            profile.setRoomName(activeRoom.displayName());
        }
        eventBus.emitRequirement(ctx.getRunId(), profile);
        messageStore.persistAssistantMessage(ctx.getSessionId(), ctx.getRunId(), "requirement",
            "需求档案已更新（v" + nextVersionNo + "）",
            objectMapper.writeValueAsString(Map.of("profile", profile)));
    }

    /**
     * 对单个作用域应用 operations 并落新版本。
     *
     * @return 应用结果；全部 operation 被拒绝时返回 null（不落版本）
     */
    private RequirementPatchReducer.ApplyResult applyScope(AgentRunContext ctx, RequirementConstraints base,
                                                           List<PatchOperation> operations, String roomId,
                                                           String source, int versionNo) throws Exception {
        RequirementPatch patch = new RequirementPatch();
        patch.setOperations(operations);
        RequirementPatchReducer.ApplyResult result = reducer.apply(base, patch);
        if (result.getAppliedCount() <= 0) {
            return null;
        }
        AgentRequirementVersion version = new AgentRequirementVersion();
        version.setVersionId(IdGenerator.generate("REQ"));
        version.setSessionId(ctx.getSessionId());
        version.setVersionNo(versionNo);
        version.setConstraints(result.getUpdated().toJson());
        version.setPatch(objectMapper.writeValueAsString(patch));
        version.setSource(source);
        version.setRoomId(roomId);
        version.setCreatedAt(LocalDateTime.now());
        requirementVersionMapper.insert(version);
        return result;
    }

    /** 拼接用户提示词：当前档案（含当前房间上下文）+ 最近对话 + 最新消息。 */
    private String buildUserPrompt(AgentRunContext ctx) {
        StringBuilder prompt = new StringBuilder();
        FloorPlanContext floorPlanContext = ctx.getFloorPlanContext();
        if (floorPlanContext != null) {
            prompt.append("当前户型空间：");
            floorPlanContext.rooms().forEach(room -> prompt.append(room.displayName()).append(' '));
            prompt.append('\n');
            FloorPlanRoomFact activeRoom = floorPlanContext.activeRoom();
            if (activeRoom != null) {
                prompt.append("当前正在选品的房间：").append(activeRoom.displayName()).append('\n');
            }
        }
        prompt.append("当前需求档案：")
            .append(ctx.getConstraints() != null ? ctx.getConstraints().toJson() : "{}")
            .append('\n');
        List<AgentMessage> history = messageStore.listBySession(ctx.getSessionId());
        if (!history.isEmpty()) {
            prompt.append("最近对话：\n");
            history.stream().skip(Math.max(0, history.size() - HISTORY_LIMIT)).forEach(message -> {
                String roleLabel = "user".equals(message.getRole()) ? "用户" : "助手";
                String content = message.getContent() == null ? "" : message.getContent();
                if (content.length() > 300) {
                    content = content.substring(0, 300) + "...";
                }
                prompt.append(roleLabel).append("：").append(content).append('\n');
            });
        }
        prompt.append("用户最新消息：").append(ctx.getUserMessage());
        return prompt.toString();
    }
}
