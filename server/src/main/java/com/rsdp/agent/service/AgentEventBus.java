package com.rsdp.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent SSE 事件总线：图节点与 HTTP 层解耦。
 *
 * <p>按 runId 注册 {@link SseEmitter}，所有事件帧 data 必带 runId + seq
 * （seq 从 1 单调递增，与前端 useAgentStream 的乱序/去重保护对齐）。
 * 心跳：超过配置秒数无事件则发 {@code : ping} 注释帧，防网关空闲断连。</p>
 *
 * <p>事件类型（与前端契约一致）：meta / node / token / requirement / cards / done / error。</p>
 */
@Slf4j
@Component
public class AgentEventBus {

    /** SSE 事件名常量。 */
    public static final String EVENT_META = "meta";
    public static final String EVENT_NODE = "node";
    public static final String EVENT_TOKEN = "token";
    public static final String EVENT_REQUIREMENT = "requirement";
    public static final String EVENT_CARDS = "cards";
    public static final String EVENT_DONE = "done";
    public static final String EVENT_ERROR = "error";

    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;
    private final Map<String, RunChannel> channels = new ConcurrentHashMap<>();

    public AgentEventBus(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.scheduler = Executors.newScheduledThreadPool(1, runnable -> {
            Thread thread = new Thread(runnable, "agent-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 注册一个 run 的 SSE 通道并启动心跳。
     *
     * @param runId            运行 ID
     * @param emitter          SSE emitter
     * @param heartbeatSeconds 心跳间隔（秒）
     */
    public void register(String runId, SseEmitter emitter, int heartbeatSeconds) {
        RunChannel channel = new RunChannel(runId, emitter);
        channel.heartbeatMillis = Math.max(heartbeatSeconds, 1) * 1000L;
        channels.put(runId, channel);
        if (heartbeatSeconds > 0) {
            channel.heartbeat = scheduler.scheduleWithFixedDelay(
                () -> sendHeartbeat(channel), heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
        }
        emitter.onCompletion(() -> closeQuietly(runId));
        emitter.onTimeout(() -> closeQuietly(runId));
        emitter.onError(e -> closeQuietly(runId));
    }

    /** 首帧 meta：{sessionId}。 */
    public void emitMeta(String runId, String sessionId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", sessionId);
        emit(runId, EVENT_META, payload);
    }

    /** 节点进入事件：{node, label}（label 为中文展示文案）。 */
    public void emitNode(String runId, String node, String label) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("node", node);
        payload.put("label", label);
        emit(runId, EVENT_NODE, payload);
    }

    /** LLM 增量 token：{text}。 */
    public void emitToken(String runId, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("text", text);
        emit(runId, EVENT_TOKEN, payload);
    }

    /** 需求档案更新：{profile}。 */
    public void emitRequirement(String runId, Object profile) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("profile", profile);
        emit(runId, EVENT_REQUIREMENT, payload);
    }

    /** 推荐卡片：{batchId, items}。 */
    public void emitCards(String runId, String batchId, Object items) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batchId", batchId);
        payload.put("items", items);
        emit(runId, EVENT_CARDS, payload);
    }

    /** 正常结束：{messageId}，随后关闭流。 */
    public void emitDone(String runId, String messageId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messageId", messageId);
        emit(runId, EVENT_DONE, payload);
        complete(runId);
    }

    /** 错误结束：{code, message}，随后关闭流。 */
    public void emitError(String runId, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("code", code);
        payload.put("message", message);
        emit(runId, EVENT_ERROR, payload);
        complete(runId);
    }

    /** 通用事件发送：自动补 runId + seq；通道已关闭时静默丢弃。 */
    public void emit(String runId, String event, Map<String, Object> payload) {
        RunChannel channel = channels.get(runId);
        if (channel == null || channel.closed.get()) {
            return;
        }
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("runId", runId);
        frame.put("seq", channel.seq.incrementAndGet());
        frame.putAll(payload);
        try {
            channel.emitter.send(SseEmitter.event()
                .name(event)
                .data(objectMapper.writeValueAsString(frame), MediaType.APPLICATION_JSON));
            channel.lastEventAt.set(System.currentTimeMillis());
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 事件发送失败（客户端可能已断开），runId={}, event={}", runId, event);
            closeQuietly(runId);
        }
    }

    /** 关闭通道（幂等）：取消心跳并 complete emitter。 */
    public void complete(String runId) {
        RunChannel channel = channels.remove(runId);
        if (channel == null || !channel.closed.compareAndSet(false, true)) {
            return;
        }
        if (channel.heartbeat != null) {
            channel.heartbeat.cancel(false);
        }
        try {
            channel.emitter.complete();
        } catch (IllegalStateException e) {
            // emitter 已被容器关闭，忽略
        }
    }

    /** 通道是否仍打开（run 超时强制关闭后，图线程的后续事件应被丢弃）。 */
    public boolean isOpen(String runId) {
        RunChannel channel = channels.get(runId);
        return channel != null && !channel.closed.get();
    }

    private void sendHeartbeat(RunChannel channel) {
        if (channel.closed.get()) {
            return;
        }
        long idleMillis = System.currentTimeMillis() - channel.lastEventAt.get();
        // 心跳周期内已有事件则跳过
        if (idleMillis < channel.heartbeatMillis) {
            return;
        }
        try {
            channel.emitter.send(SseEmitter.event().comment("ping"));
        } catch (IOException | IllegalStateException e) {
            log.warn("SSE 心跳发送失败，关闭通道，runId={}", channel.runId);
            closeQuietly(channel.runId);
        }
    }

    private void closeQuietly(String runId) {
        RunChannel channel = channels.remove(runId);
        if (channel != null && channel.closed.compareAndSet(false, true) && channel.heartbeat != null) {
            channel.heartbeat.cancel(false);
        }
    }

    /** 单个 run 的发送通道。 */
    private static final class RunChannel {

        private final String runId;
        private final SseEmitter emitter;
        private final AtomicLong seq = new AtomicLong(0);
        private final AtomicLong lastEventAt = new AtomicLong(System.currentTimeMillis());
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private long heartbeatMillis;
        private ScheduledFuture<?> heartbeat;

        private RunChannel(String runId, SseEmitter emitter) {
            this.runId = runId;
            this.emitter = emitter;
        }
    }
}
