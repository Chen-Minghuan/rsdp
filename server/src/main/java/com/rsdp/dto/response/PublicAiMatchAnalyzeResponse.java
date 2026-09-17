package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 官网 AI 户型图分析响应（免登录公开接口）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PublicAiMatchAnalyzeResponse {

    /**
     * 识别出的空间列表。
     */
    private List<RoomItem> rooms = new ArrayList<>();

    /**
     * 落库的分析批次 ID（v3.0 §4.6 策略 B：官网匿名分析落库为数据资产）；
     * 落库失败降级时为 null。
     */
    private String analysisId;

    /**
     * 自动标定建议（户型图优化二期）：status=auto/candidates/null，
     * 结构见 {@link ScaleSuggestionResponse}（前端契约，字段名不可改）。
     */
    private ScaleSuggestionResponse scaleSuggestion;

    /**
     * 单个空间的识别结果。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomItem {

        /** 空间类型枚举（living_room/bedroom/...）。 */
        private String roomType;

        /** 空间中文名（如 "客厅"）。 */
        private String roomName;

        /** 开间（mm），未解析到尺寸标注为 null。 */
        private Integer widthMm;

        /** 进深（mm），未解析到尺寸标注为 null。 */
        private Integer depthMm;

        /** 面积（平方米，两位小数），未解析到尺寸标注为 null。 */
        private BigDecimal areaM2;

        /** 图上尺寸标注原文。 */
        private String dimensionText;

        /** 尺寸置信度：high（图上标注解析成功）/ low（无标注）。 */
        private String confidence;

        /** 空间位置框左上角 x（相对原图归一化 [0,1]），未识别出位置为 null。 */
        private Double x;

        /** 空间位置框左上角 y（相对原图归一化 [0,1]）。 */
        private Double y;

        /** 空间位置框宽 w（相对原图归一化 [0,1]）。 */
        private Double w;

        /** 空间位置框高 h（相对原图归一化 [0,1]）。 */
        private Double h;
    }
}
