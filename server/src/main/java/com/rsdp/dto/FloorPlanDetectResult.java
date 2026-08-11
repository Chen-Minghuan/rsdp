package com.rsdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 户型图空间识别结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FloorPlanDetectResult {

    /**
     * 识别出的功能空间列表。
     */
    private List<Room> rooms = new ArrayList<>();

    /**
     * 图上比例尺标注原文（如 "1:50"），无标注为 null。
     */
    private String scaleText;

    /**
     * 单个功能空间。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Room {

        /**
         * 空间类型枚举：living_room / dining_room / bedroom / kitchen /
         * bathroom / balcony / study / hallway / other。
         */
        private String roomType;

        /**
         * 空间标注名原文（如 "客厅"）。
         */
        private String label;

        /**
         * 尺寸标注原文（如 "4200×3800"、"4.2m*3.8m"），无标注为 null。
         */
        private String dimensionText;

        /** 归一化坐标左上角 x [0,1]，bbox 缺失为 null。 */
        private Double x;

        /** 归一化坐标左上角 y [0,1]。 */
        private Double y;

        /** 归一化宽 w [0,1]。 */
        private Double w;

        /** 归一化高 h [0,1]。 */
        private Double h;
    }
}
