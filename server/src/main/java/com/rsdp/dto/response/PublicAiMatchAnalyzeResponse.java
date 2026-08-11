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
    }
}
