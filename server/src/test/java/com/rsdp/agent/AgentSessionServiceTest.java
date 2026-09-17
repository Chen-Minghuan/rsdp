package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsdp.agent.dto.AgentSessionResponse;
import com.rsdp.agent.entity.AgentSession;
import com.rsdp.agent.mapper.AgentConfirmedItemMapper;
import com.rsdp.agent.mapper.AgentRequirementVersionMapper;
import com.rsdp.agent.mapper.AgentSessionMapper;
import com.rsdp.agent.service.AgentMessageStore;
import com.rsdp.agent.service.AgentSessionService;
import com.rsdp.exception.BusinessException;
import com.rsdp.exception.ResourceNotFoundException;
import com.rsdp.security.SecurityOperatorContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link AgentSessionService} 单元测试（数据隔离三态 + 会话生命周期）。
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionServiceTest {

    @Mock
    private AgentSessionMapper sessionMapper;

    @Mock
    private AgentRequirementVersionMapper requirementVersionMapper;

    @Mock
    private AgentConfirmedItemMapper confirmedItemMapper;

    @Mock
    private AgentMessageStore messageStore;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private AgentSessionService sessionService;

    private AgentSession session(String sessionId, String createdBy, String customerUserId) {
        AgentSession session = new AgentSession();
        session.setSessionId(sessionId);
        session.setCreatedBy(createdBy);
        session.setCustomerUserId(customerUserId);
        session.setStatus("active");
        return session;
    }

    @Test
    void createShouldSetCreatorAndActiveStatus() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            AgentSessionResponse response = sessionService.create(" 王女士 ");

            ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
            verify(sessionMapper).insert(captor.capture());
            AgentSession saved = captor.getValue();
            assertThat(saved.getSessionId()).startsWith("SES-");
            assertThat(saved.getCreatedBy()).isEqualTo("user-1");
            assertThat(saved.getCustomerName()).isEqualTo("王女士");
            assertThat(saved.getStatus()).isEqualTo("active");
            assertThat(saved.getCurrentVersionNo()).isZero();
            assertThat(response.getSessionId()).isEqualTo(saved.getSessionId());
        }
    }

    @Test
    void createWithBlankCustomerNameShouldStoreNull() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            sessionService.create("   ");

            ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
            verify(sessionMapper).insert(captor.capture());
            assertThat(captor.getValue().getCustomerName()).isNull();
        }
    }

    @Test
    void requireAccessibleSessionShouldThrowNotFoundForUnknownSession() {
        when(sessionMapper.selectById("SES-9")).thenReturn(null);

        assertThatThrownBy(() -> sessionService.requireAccessibleSession("SES-9"))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void creatorShouldAccessOwnSession() {
        when(sessionMapper.selectById("SES-1")).thenReturn(session("SES-1", "user-1", null));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            AgentSession session = sessionService.requireAccessibleSession("SES-1");
            assertThat(session.getSessionId()).isEqualTo("SES-1");
        }
    }

    @Test
    void customerUserShouldAccessSession() {
        when(sessionMapper.selectById("SES-1")).thenReturn(session("SES-1", "designer-1", "cust-1"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("cust-1");

            AgentSession session = sessionService.requireAccessibleSession("SES-1");
            assertThat(session.getSessionId()).isEqualTo("SES-1");
        }
    }

    @Test
    void unrelatedUserShouldGet403() {
        when(sessionMapper.selectById("SES-1")).thenReturn(session("SES-1", "designer-1", "cust-1"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-9");

            assertThatThrownBy(() -> sessionService.requireAccessibleSession("SES-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403))
                .hasMessageContaining("无权访问");
        }
    }

    @Test
    void platformStaffShouldAccessAnySession() {
        when(sessionMapper.selectById("SES-1")).thenReturn(session("SES-1", "designer-1", "cust-1"));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);

            AgentSession session = sessionService.requireAccessibleSession("SES-1");
            assertThat(session.getSessionId()).isEqualTo("SES-1");
        }
    }

    @Test
    void listMineShouldScopeByCreatorOrCustomerForNonStaff() {
        when(sessionMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(session("SES-1", "user-1", null)));

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            List<AgentSessionResponse> result = sessionService.listMine();

            ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(sessionMapper).selectList(captor.capture());
            String sqlSegment = captor.getValue().getSqlSegment();
            assertThat(sqlSegment).contains("created_by").contains("customer_user_id");
            assertThat(result).hasSize(1);
        }
    }

    @Test
    void listMineShouldNotScopeForPlatformStaff() {
        when(sessionMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            // listMine 先解析当前用户 ID 再判断平台员工，需同时桩 currentUserId
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            when(SecurityOperatorContext.currentUserId()).thenReturn("staff-1");

            sessionService.listMine();

            ArgumentCaptor<QueryWrapper> captor = ArgumentCaptor.forClass(QueryWrapper.class);
            verify(sessionMapper).selectList(captor.capture());
            assertThat(captor.getValue().getSqlSegment())
                .doesNotContain("created_by")
                .doesNotContain("customer_user_id");
        }
    }

    @Test
    void closeWithRunningRunShouldReturn409() {
        AgentSession session = session("SES-1", "user-1", null);
        session.setActiveRunId("RUN-1");
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> sessionService.close("SES-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409))
                .hasMessageContaining("SESSION_BUSY");
            verify(sessionMapper, never()).updateById(any(AgentSession.class));
        }
    }

    @Test
    void closeWithoutRunningRunShouldMarkClosed() {
        AgentSession session = session("SES-1", "user-1", null);
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            AgentSessionResponse response = sessionService.close("SES-1");

            ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
            verify(sessionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo("closed");
            assertThat(response.getStatus()).isEqualTo("closed");
        }
    }
}
