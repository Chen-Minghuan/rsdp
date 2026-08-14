package com.rsdp.dto.response;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 户型图分析详情响应（接口 2，含空间列表）。
 */
@Data
public class FloorPlanAnalysisResponse {

    /** 分析批次 ID（FPA-<UUID>）。 */
    private String analysisId;

    /** 户型原图 ID。 */
    private String imageId;

    /** 户型原图访问地址。 */
    private String imageUrl;

    /** 状态：pending/analyzing/awaiting_confirm/confirmed/failed。 */
    private String status;

    /** 关联异步任务 ID。 */
    private String taskId;

    /** 比例尺（像素:实际mm），可空。 */
    private BigDecimal scaleRatio;

    /** 来源：admin（管理端）/ public（官网匿名）。 */
    private String source;

    /** 失败原因（status=failed 时透传任务错误信息）。 */
    private String errorMessage;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 空间列表（未软删，按 sort_order 升序）。 */
    private List<FloorPlanRoomResponse> rooms;
}
