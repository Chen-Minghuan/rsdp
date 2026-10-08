package com.rsdp.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 会话绑定户型的房间事实（W2，只读展示；空间事实不可经 Agent 修改）。
 */
@Data
public class AgentRoomResponse {

    private String roomId;

    /** 空间标签原文（如"主卧"，可空）。 */
    private String label;

    /** 空间类型字典码（LIVING_ROOM/BEDROOM/...）。 */
    private String roomType;

    /** 展示名（标签优先，退化类型码）。 */
    private String displayName;

    private Integer widthMm;

    private Integer depthMm;

    private BigDecimal areaM2;

    /** 是否为当前选品房间。 */
    private boolean active;
}
