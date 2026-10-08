package com.rsdp.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.agent.domain.FloorPlanContext;
import com.rsdp.agent.domain.FloorPlanRoomFact;
import com.rsdp.entity.FloorPlanAnalysis;
import com.rsdp.entity.FloorPlanRoom;
import com.rsdp.mapper.FloorPlanAnalysisMapper;
import com.rsdp.mapper.FloorPlanRoomMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 户型上下文服务：把 confirmed 户型的空间事实提供给营销 Agent。
 *
 * <p>只读已确认（status=confirmed）的户型；未确认/不存在的户型一律返回 null，
 * 会话退化为纯口头描述路径（降级铁律）。本服务不调用 LLM。</p>
 */
@Service
@RequiredArgsConstructor
public class FloorPlanContextService {

    private final FloorPlanAnalysisMapper analysisMapper;
    private final FloorPlanRoomMapper roomMapper;

    /**
     * 加载会话的户型上下文。
     *
     * @param floorPlanAnalysisId 会话绑定的户型分析 ID（可空）
     * @param activeRoomId        会话当前房间 ID（可空）
     * @return 户型上下文；未绑定/户型未确认/无房间时返回 null
     */
    public FloorPlanContext loadContext(String floorPlanAnalysisId, String activeRoomId) {
        if (!StringUtils.hasText(floorPlanAnalysisId)) {
            return null;
        }
        FloorPlanAnalysis analysis = analysisMapper.selectById(floorPlanAnalysisId);
        if (analysis == null || !"confirmed".equals(analysis.getStatus())) {
            return null;
        }
        List<FloorPlanRoomFact> rooms = listRooms(floorPlanAnalysisId);
        if (rooms.isEmpty()) {
            return null;
        }
        FloorPlanRoomFact activeRoom = null;
        if (StringUtils.hasText(activeRoomId)) {
            activeRoom = rooms.stream()
                .filter(room -> room.roomId().equals(activeRoomId))
                .findFirst()
                .orElse(null);
        }
        return new FloorPlanContext(floorPlanAnalysisId, rooms, activeRoom);
    }

    /**
     * 加载户型的全部房间事实（按 sort_order，再按 room_id 稳定排序）。
     *
     * @param analysisId 户型分析 ID
     * @return 房间事实列表（不为 null）
     */
    public List<FloorPlanRoomFact> listRooms(String analysisId) {
        return roomMapper.selectList(new QueryWrapper<FloorPlanRoom>()
                .eq("analysis_id", analysisId)
                .orderByAsc("sort_order")
                .orderByAsc("room_id"))
            .stream()
            .map(this::toFact)
            .toList();
    }

    /**
     * 加载并校验可绑定的户型：存在 + confirmed + 归属该校验人（平台员工不受限）。
     *
     * <p>注意归属口径：floor_plan_analysis.created_by 存的是 username（见 FloorPlanService），
     * 因此 operator 也必须传 username，传 userId 会导致设计师绑定自己的户型也被拒绝。</p>
     *
     * @param analysisId    户型分析 ID
     * @param operator      当前操作人 username
     * @param platformStaff 是否平台员工
     * @return 户型分析实体
     * @throws com.rsdp.exception.BusinessException 户型不存在/未确认/无权绑定时抛出
     */
    public FloorPlanAnalysis requireBindableAnalysis(String analysisId, String operator, boolean platformStaff) {
        FloorPlanAnalysis analysis = analysisMapper.selectById(analysisId);
        if (analysis == null) {
            throw new com.rsdp.exception.BusinessException("户型分析不存在: " + analysisId);
        }
        if (!"confirmed".equals(analysis.getStatus())) {
            throw new com.rsdp.exception.BusinessException(400, "户型尚未确认，请先完成空间确认");
        }
        if (!platformStaff && !operator.equals(analysis.getCreatedBy())) {
            throw new com.rsdp.exception.BusinessException(403, "无权绑定该户型");
        }
        return analysis;
    }

    private FloorPlanRoomFact toFact(FloorPlanRoom room) {
        return new FloorPlanRoomFact(room.getRoomId(), room.getLabel(), room.getRoomType(),
            room.getWidthMm(), room.getDepthMm(), room.getAreaM2());
    }
}
