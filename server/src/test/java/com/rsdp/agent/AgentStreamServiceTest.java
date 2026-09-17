package com.rsdp.agent;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.config.MarketingAgentProperties;
import com.rsdp.agent.dto.SendMessageRequest;
import com.rsdp.agent.entity.AgentMessage;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.service.AgentCheckpointStore;
import com.rsdp.agent.service.AgentEventBus;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentRunRecorder;
import com.rsdp.agent.service.AgentSessionBusyException;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.agent.service.AgentStreamService;
import com.rsdp.exception.BusinessException;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentStreamService} 同步段单元测试（client_message_id 幂等、
 * active_run_id 并发认领 409、锁释放），异步段不跑真实图。
 */
@ExtendWith(MockitoExtension.class)
class AgentStreamServiceTest {

    @Mock
    private AgentSessionService sessionService;

    @Mock
    private AgentSessionMapper sessionMapper;

    @Mock
    private AgentMessageStore messageStore;

    @Mock
    private AgentRunRecorder runRecorder;

    @Mock
    private AgentEventBus eventBus;

    @Mock
    private AgentCheckpointStore checkpointStore;

    @Mock
    private CompiledGraph marketingAgentGraph;

    @Mock
    private ThreadPoolTaskExecutor taskExecutor;

    private final MarketingAgentProperties properties = new MarketingAgentProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private AgentStreamService streamService;

    @BeforeEach
    void setUp() {
        streamService = new AgentStreamService(sessionService, sessionMapper, messageStore,
            runRecorder, eventBus, checkpointStore, marketingAgentGraph, properties,
            taskExecutor, objectMapper);
    }

    private AgentSession activeSession() {
        AgentSession session = new AgentSession();
        session.setSessionId("SES-1");
        session.setStatus("active");
        session.setCurrentVersionNo(0);
        return session;
    }

    private SendMessageRequest request() {
        SendMessageRequest request = new SendMessageRequest();
        request.setClientMessageId("cm-1");
        request.setContent("帮我找沙发");
        return request;
    }

    private AgentMessage userMessage() {
        AgentMessage message = new AgentMessage();
        message.setMessageId("MSG-1");
        message.setSessionId("SES-1");
        message.setRole("user");
        message.setMessageType("text");
        message.setSequenceNo(1L);
        return message;
    }

    @Test
    void closedSessionShouldBeRejectedBeforeClaim() {
        AgentSession closed = activeSession();
        closed.setStatus("closed");
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(closed);

        assertThatThrownBy(() -> streamService.startStream("SES-1", request()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("会话已关闭");
        verify(sessionMapper, never()).update(any(), any(UpdateWrapper.class));
    }

    @Test
    void claimedSessionShouldThrowBusy409() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
        when(messageStore.findByClientMessageId("SES-1", "cm-1")).thenReturn(null);
        // UPDATE ... WHERE active_run_id IS NULL 命中 0 行 = 已有 running run
        when(sessionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(0);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> streamService.startStream("SES-1", request()))
                .isInstanceOf(AgentSessionBusyException.class)
                .hasMessageContaining("SESSION_BUSY");
            verify(messageStore, never()).persistUserMessage(any(), any(), any());
        }
    }

    @Test
    void duplicateClientMessageIdShouldReplayWithoutClaiming() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
        AgentMessage existing = userMessage();
        when(messageStore.findByClientMessageId("SES-1", "cm-1")).thenReturn(existing);
        AgentMessage assistant = new AgentMessage();
        assistant.setMessageId("MSG-2");
        assistant.setRunId("RUN-9");
        assistant.setRole("assistant");
        assistant.setMessageType("text");
        assistant.setContent("推荐如下");
        when(messageStore.findNextAssistantMessage("SES-1", 1L)).thenReturn(assistant);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            SseEmitter emitter = streamService.startStream("SES-1", request());

            assertThat(emitter).isNotNull();
            // 幂等重放：不做并发认领、不重复落库、不跑 LLM
            verify(sessionMapper, never()).update(any(), any(UpdateWrapper.class));
            verify(messageStore, never()).persistUserMessage(any(), any(), any());
            verify(eventBus).register(eq("RUN-9"), any(SseEmitter.class), eq(0));
        }
    }

    @Test
    void successfulClaimShouldRegisterEmitterAndDispatchRun() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
        when(messageStore.findByClientMessageId("SES-1", "cm-1")).thenReturn(null);
        when(sessionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(messageStore.persistUserMessage("SES-1", "cm-1", "帮我找沙发")).thenReturn(userMessage());
        when(messageStore.listBySession("SES-1")).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            SseEmitter emitter = streamService.startStream("SES-1", request());

            assertThat(emitter).isNotNull();
            // 并发认领：条件 UPDATE 抢占 active_run_id
            verify(sessionMapper).update(isNull(), any(UpdateWrapper.class));
            // run 记录创建 + SSE 通道注册 + 异步派发
            ArgumentCaptor<com.rsdp.agent.entity.AgentRun> runCaptor =
                ArgumentCaptor.forClass(com.rsdp.agent.entity.AgentRun.class);
            verify(runRecorder).start(runCaptor.capture());
            assertThat(runCaptor.getValue().getRunId()).startsWith("RUN-");
            assertThat(runCaptor.getValue().getSessionId()).isEqualTo("SES-1");
            verify(eventBus).register(eq(runCaptor.getValue().getRunId()), any(SseEmitter.class),
                eq(properties.getSseHeartbeatSeconds()));
            verify(taskExecutor).execute(any(Runnable.class));
        }
    }

    @Test
    void duplicateKeyOnPersistShouldReleaseClaimAndReplay() {
        when(sessionService.requireAccessibleSession("SES-1")).thenReturn(activeSession());
        AgentMessage duplicated = userMessage();
        // 先查没有，落库撞唯一索引，再查命中（并发同 clientMessageId）
        when(messageStore.findByClientMessageId("SES-1", "cm-1")).thenReturn(null, duplicated);
        when(sessionMapper.update(isNull(), any(UpdateWrapper.class))).thenReturn(1);
        when(messageStore.persistUserMessage("SES-1", "cm-1", "帮我找沙发"))
            .thenThrow(new DuplicateKeyException("duplicate client_message_id"));
        AgentMessage assistant = new AgentMessage();
        assistant.setMessageId("MSG-2");
        assistant.setRunId("RUN-8");
        assistant.setContent("推荐如下");
        when(messageStore.findNextAssistantMessage("SES-1", 1L)).thenReturn(assistant);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            SseEmitter emitter = streamService.startStream("SES-1", request());

            assertThat(emitter).isNotNull();
            // 认领一次 + 释放一次
            verify(sessionMapper, times(2)).update(isNull(), any(UpdateWrapper.class));
            verify(eventBus).register(eq("RUN-8"), any(SseEmitter.class), eq(0));
        }
    }
}
