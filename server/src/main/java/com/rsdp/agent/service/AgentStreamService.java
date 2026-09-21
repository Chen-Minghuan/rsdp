package com.rsdp.agent.service;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.dto.SendMessageRequest;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentRequirementVersion;
import com.rsdp.agent.entity.AgentRun;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.graph.AgentStateKeys;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.patch.RequirementConstraints;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import com.rsdp.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Agent SSE 流编排：一轮用户消息 = 一次 Agent Run（run 边界即 HITL 边界）。
 *
 * <p>同步段（HTTP 线程）：会话校验（存在/权限/active）→ client_message_id 幂等检查
 * → active_run_id 并发认领（UPDATE ... WHERE active_run_id IS NULL，0 行 = 409）
 * → 用户消息落库 → run 记录创建 → 注册 SSE 通道。异步段（taskExecutor）：
 * meta 首帧 → 跑图（节点经 {@link AgentEventBus} 发事件）→ assistant 消息落库 → done。</p>
 *
 * <p>任何路径结束都要释放 active_run_id（finally 条件置 NULL，仅释放本 run 的锁）；
 * run 超过 {@code runTimeoutSeconds} 强制 error 超时并释放。</p>
 */
@Slf4j
@Service
public class AgentStreamService {

    private final AgentSessionService sessionService;
    private final AgentSessionMapper sessionMapper;
    private final AgentMessageStore messageStore;
    private final AgentRunRecorder runRecorder;
    private final AgentEventBus eventBus;
    private final AgentCheckpointStore checkpointStore;
    private final CompiledGraph marketingAgentGraph;
    private final MarketingAgentProperties properties;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService timeoutScheduler;
    private final Map<String, ScheduledFuture<?>> timeoutTasks = new ConcurrentHashMap<>();

    public AgentStreamService(AgentSessionService sessionService, AgentSessionMapper sessionMapper,
                              AgentMessageStore messageStore, AgentRunRecorder runRecorder,
                              AgentEventBus eventBus, AgentCheckpointStore checkpointStore,
                              CompiledGraph marketingAgentGraph, MarketingAgentProperties properties,
                              @Qualifier("taskExecutor") ThreadPoolTaskExecutor taskExecutor,
                              ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.sessionMapper = sessionMapper;
        this.messageStore = messageStore;
        this.runRecorder = runRecorder;
        this.eventBus = eventBus;
        this.checkpointStore = checkpointStore;
        this.marketingAgentGraph = marketingAgentGraph;
        this.properties = properties;
        this.taskExecutor = taskExecutor;
        this.objectMapper = objectMapper;
        this.timeoutScheduler = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "agent-run-timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 发起一轮流式对话（同步段，校验通过后返回 emitter，图在异步线程执行）。
     *
     * @param sessionId 会话 ID
     * @param request   消息请求
     * @return SSE emitter
     * @throws AgentSessionBusyException 会话已有 running run（Controller 转 HTTP 409）
     * @throws BusinessException         会话不存在/无权/已关闭
     */
    public SseEmitter startStream(String sessionId, SendMessageRequest request) {
        AgentSession session = sessionService.requireAccessibleSession(sessionId);
        if (!"active".equals(session.getStatus())) {
            throw new BusinessException(400, "会话已关闭");
        }
        String operatorUserId = SecurityOperatorContext.currentUserId();

        // client_message_id 幂等：已处理过的消息直接重放，不重复跑 LLM
        AgentMessage existingUserMessage = messageStore.findByClientMessageId(sessionId, request.getClientMessageId());
        if (existingUserMessage != null) {
            return replay(sessionId, existingUserMessage);
        }

        // 并发认领：一会话最多一个 running run
        String runId = IdGenerator.generate("RUN");
        int claimed = sessionMapper.update(null, new UpdateWrapper<AgentSession>()
            .eq("session_id", sessionId)
            .isNull("active_run_id")
            .set("active_run_id", runId)
            .set("updated_at", LocalDateTime.now()));
        if (claimed == 0) {
            throw new AgentSessionBusyException("SESSION_BUSY");
        }

        AgentRunContext ctx = null;
        try {
            AgentMessage userMessage;
            try {
                userMessage = messageStore.persistUserMessage(sessionId, request.getClientMessageId(), request.getContent());
            } catch (DuplicateKeyException e) {
                // 并发下同 clientMessageId 已落库：释放认领，走重放
                releaseClaim(sessionId, runId);
                AgentMessage duplicated = messageStore.findByClientMessageId(sessionId, request.getClientMessageId());
                return duplicated != null ? replay(sessionId, duplicated) : emptyReplay(sessionId);
            }

            AgentRun run = new AgentRun();
            run.setRunId(runId);
            run.setSessionId(sessionId);
            runRecorder.start(run);

            ctx = new AgentRunContext(sessionId, runId, operatorUserId, request.getContent());
            loadRequirement(ctx, session);
            ctx.setFollowupCount(countConsecutiveFollowups(sessionId));
            AgentRunContext.register(ctx);
        } catch (RuntimeException e) {
            releaseClaim(sessionId, runId);
            if (ctx != null) {
                AgentRunContext.remove(runId);
            }
            runRecorder.markFailed(runId, "RUN_FAILED");
            throw e;
        }

        long emitterTimeoutMillis = (properties.getRunTimeoutSeconds() + 30L) * 1000L;
        SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
        eventBus.register(runId, emitter, properties.getSseHeartbeatSeconds());
        scheduleRunTimeout(sessionId, runId);
        AgentRunContext finalCtx = ctx;
        taskExecutor.execute(() -> executeRun(finalCtx));
        return emitter;
    }

    /**
     * 组装 assistant 消息 metadata：followup 标记 + 本 run 节点轨迹（思考过程，前端折叠面板展示）。
     * 两者皆无时返回 null。
     */
    public String buildMessageMetadata(AgentRunContext ctx) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            if (ctx.isFollowup()) {
                metadata.put("followup", true);
            }
            if (!ctx.steps().isEmpty()) {
                metadata.put("steps", ctx.steps());
            }
            return metadata.isEmpty() ? null : objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            throw new IllegalStateException("assistant 消息 metadata 序列化失败", e);
        }
    }

    /** 异步段：跑图 → assistant 文本落库 → done/error → 释放认领。 */
    private void executeRun(AgentRunContext ctx) {        String runId = ctx.getRunId();
        String sessionId = ctx.getSessionId();
        try {
            eventBus.emitMeta(runId, sessionId);
            Map<String, Object> initialState = Map.of(
                AgentStateKeys.STATE_SESSION_ID, sessionId,
                AgentStateKeys.STATE_RUN_ID, runId,
                AgentStateKeys.STATE_USER_MESSAGE, ctx.getUserMessage());
            marketingAgentGraph.invoke(initialState, RunnableConfig.builder().threadId(runId).build());
            if (!eventBus.isOpen(runId)) {
                // 已超时强制关闭，不再落库/发帧
                return;
            }

            String text = ctx.assistantText().trim();
            if (text.isEmpty()) {
                text = "好的。";
            }
            String messageType = ctx.isNotice() ? "notice" : "text";
            String metadata = buildMessageMetadata(ctx);
            AgentMessage assistant = messageStore.persistAssistantMessage(
                sessionId, runId, messageType, text, metadata);
            runRecorder.markDone(runId);
            checkpointStore.latestCheckpointId(runId)
                .ifPresent(checkpointId -> runRecorder.updateCheckpointId(runId, checkpointId));
            eventBus.emitDone(runId, assistant.getMessageId());
        } catch (Exception e) {
            log.error("Agent Run 执行失败，runId={}", runId, e);
            runRecorder.markFailed(runId, "RUN_FAILED");
            eventBus.emitError(runId, "RUN_FAILED", "运行失败，请稍后重试");
        } finally {
            cancelRunTimeout(runId);
            releaseClaim(sessionId, runId);
            eventBus.complete(runId);
            AgentRunContext.remove(runId);
        }
    }

    /** run 超时：发 error 帧、标记失败、释放认领；图线程的后续事件因通道关闭被丢弃。 */
    private void scheduleRunTimeout(String sessionId, String runId) {
        ScheduledFuture<?> task = timeoutScheduler.schedule(() -> {
            if (!eventBus.isOpen(runId)) {
                return;
            }
            log.error("Agent Run 超时强制结束，runId={}", runId);
            runRecorder.markFailed(runId, "RUN_TIMEOUT");
            eventBus.emitError(runId, "RUN_TIMEOUT", "处理超时，请稍后重试");
            releaseClaim(sessionId, runId);
            AgentRunContext.remove(runId);
        }, properties.getRunTimeoutSeconds(), TimeUnit.SECONDS);
        timeoutTasks.put(runId, task);
    }

    private void cancelRunTimeout(String runId) {
        ScheduledFuture<?> task = timeoutTasks.remove(runId);
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 条件释放并发认领（仅当当前持锁者是本 run，避免误释放超时后新 run 的锁）。 */
    private void releaseClaim(String sessionId, String runId) {
        try {
            sessionMapper.update(null, new UpdateWrapper<AgentSession>()
                .eq("session_id", sessionId)
                .eq("active_run_id", runId)
                .set("active_run_id", null)
                .set("updated_at", LocalDateTime.now()));
        } catch (Exception e) {
            log.error("释放会话 active_run_id 失败，sessionId={}, runId={}", sessionId, runId, e);
        }
    }

    /** 幂等重放：meta + 已存 assistant 文本（一条 token 全量）+ done，不跑 LLM。 */
    private SseEmitter replay(String sessionId, AgentMessage userMessage) {
        AgentMessage assistant = messageStore.findNextAssistantMessage(sessionId, userMessage.getSequenceNo());
        String runId = assistant != null && assistant.getRunId() != null
            ? assistant.getRunId() : "REPLAY-" + userMessage.getMessageId();
        SseEmitter emitter = new SseEmitter(30_000L);
        eventBus.register(runId, emitter, 0);
        taskExecutor.execute(() -> {
            try {
                eventBus.emitMeta(runId, sessionId);
                if (assistant != null) {
                    if (assistant.getContent() != null && !assistant.getContent().isEmpty()) {
                        eventBus.emitToken(runId, assistant.getContent());
                    }
                    eventBus.emitDone(runId, assistant.getMessageId());
                } else {
                    // 用户消息已落库但 run 未完成（上次失败/中断）：不重跑，提示重发
                    eventBus.emitError(runId, "RUN_NOT_FINISHED", "上一轮运行未完成，请稍后重试");
                }
            } finally {
                eventBus.complete(runId);
            }
        });
        return emitter;
    }

    /** 极端兜底：用户消息疑似落库但查不到时的空重放。 */
    private SseEmitter emptyReplay(String sessionId) {
        SseEmitter emitter = new SseEmitter(30_000L);
        String runId = IdGenerator.generate("RUN");
        eventBus.register(runId, emitter, 0);
        taskExecutor.execute(() -> {
            try {
                eventBus.emitMeta(runId, sessionId);
                eventBus.emitError(runId, "RUN_NOT_FINISHED", "消息已受理，请刷新会话查看");
            } finally {
                eventBus.complete(runId);
            }
        });
        return emitter;
    }

    /** 加载当前需求版本到运行上下文（无版本时为空档案 v0）。 */
    private void loadRequirement(AgentRunContext ctx, AgentSession session) {
        AgentRequirementVersion latest = sessionService.latestRequirementVersion(session.getSessionId());
        if (latest != null) {
            ctx.setConstraints(RequirementConstraints.fromJson(latest.getConstraints()));
            ctx.setVersionNo(latest.getVersionNo());
        } else {
            ctx.setConstraints(new RequirementConstraints());
            ctx.setVersionNo(session.getCurrentVersionNo() != null ? session.getCurrentVersionNo() : 0);
        }
    }

    /**
     * 统计会话内连续追问轮次：从最新消息倒序，跳过用户消息，
     * 累计带 followup 标记的 assistant 文本消息，遇到其它类型消息停止。
     */
    private int countConsecutiveFollowups(String sessionId) {
        List<AgentMessage> messages = messageStore.listBySession(sessionId);
        int count = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage message = messages.get(i);
            if ("user".equals(message.getRole())) {
                continue;
            }
            if ("assistant".equals(message.getRole())
                && "text".equals(message.getMessageType())
                && isFollowupMessage(message)) {
                count++;
            } else {
                break;
            }
        }
        return count;
    }

    private boolean isFollowupMessage(AgentMessage message) {
        String metadata = message.getMetadata();
        if (metadata == null || metadata.isBlank()) {
            return false;
        }
        try {
            Object value = objectMapper.readValue(metadata, Map.class).get("followup");
            return Boolean.TRUE.equals(value);
        } catch (Exception e) {
            return false;
        }
    }}
