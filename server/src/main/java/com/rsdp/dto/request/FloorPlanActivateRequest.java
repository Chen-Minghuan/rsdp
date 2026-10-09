package com.rsdp.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Scene 原子激活请求（V20，设计文档 V1.2.3 §8.5）。
 *
 * <p>激活为显式、整版、单事务原子操作：校验全部 expected 版本后切换
 * active_analysis_revision 指针；任何一步失败整体回滚。</p>
 */
@Data
public class FloorPlanActivateRequest {

    /** 激活前的 active Analysis Revision（可为 null 表示此前无激活版本）。 */
    private Integer baseActiveAnalysisRevision;

    /** 期望的 Room 对象版本（必填，与目标 Revision 当前 room_revision 一致才允许激活）。 */
    @NotNull(message = "expectedRoomRevision 不能为空")
    private Integer expectedRoomRevision;

    /** 期望的 Furniture 对象版本（必填）。 */
    @NotNull(message = "expectedFurnitureRevision 不能为空")
    private Integer expectedFurnitureRevision;
}
