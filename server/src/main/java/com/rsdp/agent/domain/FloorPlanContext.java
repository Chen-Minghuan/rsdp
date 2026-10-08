package com.rsdp.agent.domain;

import java.util.List;

/**
 * 会话绑定的户型上下文（confirmed 户型的空间事实 + 当前房间）。
 *
 * @param analysisId  户型分析 ID
 * @param rooms       全部房间事实（按 sort_order）
 * @param activeRoom  当前正在选品的房间（可空=未选择）
 */
public record FloorPlanContext(String analysisId, List<FloorPlanRoomFact> rooms, FloorPlanRoomFact activeRoom) {

    /** 按房间 ID 查找（找不到返回 null）。 */
    public FloorPlanRoomFact roomById(String roomId) {
        if (roomId == null) {
            return null;
        }
        return rooms.stream().filter(r -> r.roomId().equals(roomId)).findFirst().orElse(null);
    }
}
