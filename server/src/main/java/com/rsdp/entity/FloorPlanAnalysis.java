package com.rsdp.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rsdp.config.typehandler.JsonbTypeHandler;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 户型图分析批次实体（V36，一次上传一条）。
 *
 * <p>状态机：pending → analyzing → awaiting_confirm → confirmed；失败为 failed。</p>
 */
@Data
@TableName("floor_plan_analysis")
public class FloorPlanAnalysis {

    @TableId
    private String analysisId;
    private String imageId;
    /** CAD 规范预览图 ID；与房间 polygon 共用 drawingBounds，视觉通道为空。 */
    private String previewImageId;
    private String status;
    private String taskId;

    /** AI 原始识别结果（不动，留档）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String rawResult;

    /** 人工校正后的空间列表（最终生效数据）。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String confirmedRooms;

    /** 识别/人工确认的比例尺（像素:实际mm），可空。 */
    private BigDecimal scaleRatio;

    /** 来源：admin（管理端）/ public（官网匿名）。 */
    private String source;

    /** 归属项目 ID（V14，可空：官网匿名/未归属）。 */
    private String projectId;

    /** 户型名称/备注（V14，历史列表辨识用），可空。 */
    private String sourceName;

    /** 解析质量提示数组（V14，由 raw_result 冗余提升，识别完成时写入），JSONB。 */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String qualityIssues;

    private String errorMessage;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableLogic(value = "null", delval = "now()")
    private LocalDateTime deletedAt;
}
