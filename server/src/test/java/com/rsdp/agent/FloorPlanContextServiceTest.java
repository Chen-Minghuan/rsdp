package com.rsdp.agent;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.service.FloorPlanContextService;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.exception.BusinessException;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FloorPlanContextService} 单元测试（confirmed 前置与归属校验守卫）。
 */
class FloorPlanContextServiceTest {

    private FloorPlanAnalysisMapper analysisMapper;
    private FloorPlanRoomMapper roomMapper;
    private FloorPlanContextService service;

    @BeforeEach
    void setUp() {
        analysisMapper = mock(FloorPlanAnalysisMapper.class);
        roomMapper = mock(FloorPlanRoomMapper.class);
        service = new FloorPlanContextService(analysisMapper, roomMapper);
    }

    private FloorPlanAnalysis analysis(String status, String createdBy) {
        FloorPlanAnalysis a = new FloorPlanAnalysis();
        a.setAnalysisId("FPA-1");
        a.setStatus(status);
        a.setCreatedBy(createdBy);
        return a;
    }

    private FloorPlanRoom room(String roomId, String label, int sortOrder) {
        FloorPlanRoom r = new FloorPlanRoom();
        r.setRoomId(roomId);
        r.setAnalysisId("FPA-1");
        r.setLabel(label);
        r.setRoomType("LIVING_ROOM");
        r.setWidthMm(5200);
        r.setDepthMm(4300);
        r.setAreaM2(new BigDecimal("22.36"));
        r.setSortOrder(sortOrder);
        return r;
    }

    @Test
    void blankAnalysisIdShouldReturnNull() {
        assertThat(service.loadContext(null, null)).isNull();
        assertThat(service.loadContext("  ", null)).isNull();
    }

    @Test
    void unconfirmedAnalysisShouldReturnNull() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("processing", "user-1"));
        assertThat(service.loadContext("FPA-1", null)).isNull();
    }

    @Test
    void confirmedWithoutRoomsShouldReturnNull() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-1"));
        when(roomMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        assertThat(service.loadContext("FPA-1", null)).isNull();
    }

    @Test
    void confirmedWithRoomsShouldResolveActiveRoom() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-1"));
        when(roomMapper.selectList(any(QueryWrapper.class)))
            .thenReturn(List.of(room("ROOM-1", "客厅", 0), room("ROOM-2", "主卧", 1)));

        FloorPlanContext context = service.loadContext("FPA-1", "ROOM-2");

        assertThat(context).isNotNull();
        assertThat(context.rooms()).hasSize(2);
        assertThat(context.activeRoom()).isNotNull();
        assertThat(context.activeRoom().roomId()).isEqualTo("ROOM-2");
        assertThat(context.activeRoom().displayName()).isEqualTo("主卧");
    }

    @Test
    void unknownActiveRoomShouldFallBackToNullActiveRoom() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-1"));
        when(roomMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of(room("ROOM-1", "客厅", 0)));

        FloorPlanContext context = service.loadContext("FPA-1", "ROOM-9");

        assertThat(context).isNotNull();
        assertThat(context.activeRoom()).isNull();
    }

    @Test
    void requireBindableShouldRejectMissingAnalysis() {
        when(analysisMapper.selectById("FPA-9")).thenReturn(null);
        assertThatThrownBy(() -> service.requireBindableAnalysis("FPA-9", "user-1", false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不存在");
    }

    @Test
    void requireBindableShouldRejectUnconfirmed() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("processing", "user-1"));
        assertThatThrownBy(() -> service.requireBindableAnalysis("FPA-1", "user-1", false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未确认");
    }

    @Test
    void requireBindableShouldRejectOtherUsersAnalysis() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-2"));
        assertThatThrownBy(() -> service.requireBindableAnalysis("FPA-1", "user-1", false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("无权");
    }

    @Test
    void requireBindableShouldAllowPlatformStaff() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-2"));
        assertThat(service.requireBindableAnalysis("FPA-1", "user-1", true).getAnalysisId()).isEqualTo("FPA-1");
    }

    @Test
    void requireBindableShouldAllowOwner() {
        when(analysisMapper.selectById("FPA-1")).thenReturn(analysis("confirmed", "user-1"));
        assertThat(service.requireBindableAnalysis("FPA-1", "user-1", false)).isNotNull();
    }
}
