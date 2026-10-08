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
import static org.mockito.ArgumentMatchers.anyString;
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

    @Mock
    private com.rsdp.agent.service.FloorPlanContextService floorPlanContextService;

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

    @Test
    void deleteWithoutRunningRunShouldSoftDelete() {
        AgentSession session = session("SES-1", "user-1", null);
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            sessionService.delete("SES-1");

            verify(sessionMapper).deleteById("SES-1");
        }
    }

    @Test
    void deleteWithRunningRunShouldReturn409() {
        AgentSession session = session("SES-1", "user-1", null);
        session.setActiveRunId("RUN-1");
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");

            assertThatThrownBy(() -> sessionService.delete("SES-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409))
                .hasMessageContaining("SESSION_BUSY");
            verify(sessionMapper, never()).deleteById(anyString());
        }
    }

    @Test
    void deleteOthersSessionShouldReturn403() {
        AgentSession session = session("SES-1", "user-1", null);
        when(sessionMapper.selectById("SES-1")).thenReturn(session);

        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-9");

            assertThatThrownBy(() -> sessionService.delete("SES-1"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(403));
            verify(sessionMapper, never()).deleteById(anyString());
        }
    }

    // ==================== W2 户型绑定 ====================

    @Test
    void createWithFloorPlanShouldValidateAndSetDefaultActiveRoom() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            // floor_plan_analysis.created_by 存 username，绑定校验必须传 username 而非 userId（回归守卫）
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            com.rsdp.entity.FloorPlanAnalysis analysis = new com.rsdp.entity.FloorPlanAnalysis();
            analysis.setAnalysisId("FPA-1");
            when(floorPlanContextService.requireBindableAnalysis("FPA-1", "designer", false))
                .thenReturn(analysis);
            when(floorPlanContextService.listRooms("FPA-1")).thenReturn(java.util.List.of(
                new com.rsdp.agent.domain.FloorPlanRoomFact("ROOM-1", "客厅", "LIVING_ROOM", 5200, 4300, null),
                new com.rsdp.agent.domain.FloorPlanRoomFact("ROOM-2", "主卧", "MASTER_BEDROOM", 4200, 3800, null)));

            sessionService.create("王女士", "FPA-1");

            ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
            verify(sessionMapper).insert(captor.capture());
            assertThat(captor.getValue().getFloorPlanAnalysisId()).isEqualTo("FPA-1");
            assertThat(captor.getValue().getActiveRoomId()).isEqualTo("ROOM-1");
            // 会话归属仍记 userId
            assertThat(captor.getValue().getCreatedBy()).isEqualTo("user-1");
            verify(floorPlanContextService).requireBindableAnalysis("FPA-1", "designer", false);
        }
    }

    @Test
    void createWithUnbindableFloorPlanShouldThrowAndNotInsert() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.currentUserId()).thenReturn("user-1");
            when(SecurityOperatorContext.currentUsername()).thenReturn("designer");
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(false);
            when(floorPlanContextService.requireBindableAnalysis("FPA-9", "designer", false))
                .thenThrow(new BusinessException(400, "户型尚未确认，请先完成空间确认"));

            assertThatThrownBy(() -> sessionService.create(null, "FPA-9"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("尚未确认");
            verify(sessionMapper, never()).insert(any(AgentSession.class));
        }
    }

    @Test
    void activateRoomShouldUpdateWhenRoomBelongsToAnalysis() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            AgentSession session = session("SES-1", "user-1", null);
            session.setFloorPlanAnalysisId("FPA-1");
            when(sessionMapper.selectById("SES-1")).thenReturn(session);
            when(floorPlanContextService.loadContext("FPA-1", null))
                .thenReturn(new com.rsdp.agent.domain.FloorPlanContext("FPA-1", java.util.List.of(
                    new com.rsdp.agent.domain.FloorPlanRoomFact("ROOM-1", "客厅", "LIVING_ROOM", 5200, 4300, null),
                    new com.rsdp.agent.domain.FloorPlanRoomFact("ROOM-2", "主卧", "MASTER_BEDROOM", 4200, 3800, null)
                ), null));

            sessionService.activateRoom("SES-1", "ROOM-2");

            ArgumentCaptor<AgentSession> captor = ArgumentCaptor.forClass(AgentSession.class);
            verify(sessionMapper).updateById(captor.capture());
            assertThat(captor.getValue().getActiveRoomId()).isEqualTo("ROOM-2");
        }
    }

    @Test
    void activateRoomShouldRejectRoomOutsideAnalysis() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            AgentSession session = session("SES-1", "user-1", null);
            session.setFloorPlanAnalysisId("FPA-1");
            when(sessionMapper.selectById("SES-1")).thenReturn(session);
            when(floorPlanContextService.loadContext("FPA-1", null))
                .thenReturn(new com.rsdp.agent.domain.FloorPlanContext("FPA-1", java.util.List.of(
                    new com.rsdp.agent.domain.FloorPlanRoomFact("ROOM-1", "客厅", "LIVING_ROOM", 5200, 4300, null)
                ), null));

            assertThatThrownBy(() -> sessionService.activateRoom("SES-1", "ROOM-9"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不属于当前户型");
            verify(sessionMapper, never()).updateById(any(AgentSession.class));
        }
    }

    @Test
    void activateRoomShouldRejectSessionWithoutFloorPlan() {
        try (var ignored = mockStatic(SecurityOperatorContext.class)) {
            when(SecurityOperatorContext.isPlatformStaff()).thenReturn(true);
            AgentSession session = session("SES-1", "user-1", null);
            when(sessionMapper.selectById("SES-1")).thenReturn(session);

            assertThatThrownBy(() -> sessionService.activateRoom("SES-1", "ROOM-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未绑定户型");
        }
    }

    @Test
    void effectiveConstraintsShouldMergeGlobalAndRoomScopes() {
        com.rsdp.agent.entity.AgentRequirementVersion global =
            new com.rsdp.agent.entity.AgentRequirementVersion();
        global.setVersionNo(1);
        global.setConstraints("{\"style\":\"现代意式\",\"budgetMax\":100000,\"color\":\"米白\"}");
        com.rsdp.agent.entity.AgentRequirementVersion room =
            new com.rsdp.agent.entity.AgentRequirementVersion();
        room.setVersionNo(2);
        room.setConstraints("{\"categoryCode\":\"床\",\"color\":\"暖棕\"}");
        when(requirementVersionMapper.selectOne(any(QueryWrapper.class)))
            .thenReturn(global)
            .thenReturn(room);

        com.rsdp.agent.patch.RequirementConstraints effective =
            sessionService.effectiveConstraints("SES-1", "ROOM-2");

        assertThat(effective.getStyle()).isEqualTo("现代意式");
        assertThat(effective.getCategoryCode()).isEqualTo("床");
        assertThat(effective.getColor()).isEqualTo("暖棕");
    }

    @Test
    void effectiveConstraintsWithoutActiveRoomShouldReturnGlobalOnly() {
        com.rsdp.agent.entity.AgentRequirementVersion global =
            new com.rsdp.agent.entity.AgentRequirementVersion();
        global.setVersionNo(1);
        global.setConstraints("{\"style\":\"MC\",\"categoryCode\":\"沙发\"}");
        when(requirementVersionMapper.selectOne(any(QueryWrapper.class))).thenReturn(global);

        com.rsdp.agent.patch.RequirementConstraints effective =
            sessionService.effectiveConstraints("SES-1", null);

        assertThat(effective.getStyle()).isEqualTo("MC");
        assertThat(effective.getCategoryCode()).isEqualTo("沙发");
    }
}
