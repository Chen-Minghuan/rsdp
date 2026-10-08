package com.rsdp.agent.domain;

import java.math.BigDecimal;

/**
 * 户型房间事实（来自 confirmed 户型的 floor_plan_room，只读）。
 *
 * <p>铁律：空间事实不进入 RequirementPatch，Agent/LLM 不可修改；
 * 仅作为检索硬约束与推荐理由上下文使用。</p>
 *
 * @param roomId    房间 ID
 * @param label     空间标签原文（如"主卧"，可空）
 * @param roomType  空间类型字典码（LIVING_ROOM/BEDROOM/...）
 * @param widthMm   开间 mm（人工校正后为准，可空）
 * @param depthMm   进深 mm（可空）
 * @param areaM2    面积（可空）
 */
public record FloorPlanRoomFact(String roomId, String label, String roomType,
                                Integer widthMm, Integer depthMm, BigDecimal areaM2) {

    /** 展示名：标签原文优先，退化为空间类型码。 */
    public String displayName() {
        return label != null && !label.isBlank() ? label : roomType;
    }
}
