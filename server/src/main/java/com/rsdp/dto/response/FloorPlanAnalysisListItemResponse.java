package com.rsdp.dto.response;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 户型图分析历史列表行（管理端，方案 v3.0 §8 P1「analysis 历史列表页」）。
 *
 * <p>roomCount 为该批次下未软删空间数，由列表查询按页内 analysisId 批量统计（避免 N+1）。</p>
 */
@Data
public class FloorPlanAnalysisListItemResponse {

    private String analysisId;

    /** 状态：pending/analyzing/awaiting_confirm/confirmed/failed。 */
    private String status;

    /** 来源：admin（管理端）/ public（官网匿名）。 */
    private String source;

    /** 未软删空间数。 */
    private Long roomCount;

    /** 创建人用户名；官网匿名来源为 null。 */
    private String createdBy;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 失败原因（status=failed 时有值）。 */
    private String errorMessage;

    /** 缩略图访问地址（V14）：CAD 规范预览图优先，空则回退户型原图；均无图为 null。 */
    private String thumbnailUrl;

    /** 归属项目 ID（V14），无归属为 null。 */
    private String projectId;

    /** 归属项目名称（JOIN project 取 project_name），无归属为 null。 */
    private String projectName;

    /** 户型名称/备注（V14），可空。 */
    private String sourceName;

    /** 几何来源（V14）：批次下任一空间为 cad_geometry 则 cad_geometry，否则 ai_vision；无空间为 null。 */
    private String geometrySource;

    /** 解析质量提示数（V14，quality_issues 数组长度；无提示为 0）。 */
    private Integer qualityIssueCount;
}
