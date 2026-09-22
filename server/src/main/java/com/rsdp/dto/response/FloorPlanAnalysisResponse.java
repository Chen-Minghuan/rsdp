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

    /** 可直接展示的用户参考图地址；原始文件为 DWG/DXF 时为空。 */
    private String referenceImageUrl;

    /** CAD 规范预览图 ID；视觉通道为空。 */
    private String previewImageId;

    /** CAD 规范预览访问地址；作为 CAD 多边形叠加的默认底图。 */
    private String previewUrl;

    /** 状态：pending/analyzing/awaiting_confirm/confirmed/failed。 */
    private String status;

    /** 关联异步任务 ID。 */
    private String taskId;

    /** 比例尺（像素:实际mm），可空。 */
    private BigDecimal scaleRatio;

    /** 来源：admin（管理端）/ public（官网匿名）。 */
    private String source;

    /** 归属项目 ID（V14），无归属为 null。 */
    private String projectId;

    /** 归属项目名称（JOIN project 取 project_name），无归属为 null。 */
    private String projectName;

    /** 户型名称/备注（V14），可空。 */
    private String sourceName;

    /** 失败原因（status=failed 时透传任务错误信息）。 */
    private String errorMessage;

    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 空间列表（未软删，按 sort_order 升序）。 */
    private List<FloorPlanRoomResponse> rooms;

    /**
     * 几何来源（CAD 户型导入 P3，前端契约，字段名不可改）：
     * cad_geometry（CAD 解析，跳过标定）/ ai_vision（视觉识别）。
     */
    private String geometrySource;

    /**
     * 质量问题清单（CAD 解析质量门报告；视觉识别通道恒为空数组）。
     * 前端契约，字段名不可改；无问题为空数组而非 null。
     */
    private List<FloorPlanQualityIssue> qualityIssues;

    /**
     * 自动标定建议（户型图优化二期）：status=auto/candidates/null，
     * 结构见 {@link ScaleSuggestionResponse}（前端契约，字段名不可改）。
     */
    private ScaleSuggestionResponse scaleSuggestion;

    /**
     * CAD 图纸外包络（毫米坐标系范围，CAD 户型导入增强·图片+CAD 双文件通道）：
     * 前端用它把 rooms[].polygon 毫米坐标归一化叠加到底图上；自 raw_result 提取，
     * 视觉识别通道恒为 null（前端契约，字段名不可改）。
     */
    private FloorPlanDrawingBounds drawingBounds;

    /** CAD 规范预览对应的毫米坐标范围；缺失时前端可回退 drawingBounds。 */
    private FloorPlanDrawingBounds previewBounds;
}
