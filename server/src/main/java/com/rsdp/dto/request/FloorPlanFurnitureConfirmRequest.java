package com.rsdp.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型 Scene 家具人工校正提交请求（V20，设计文档 V1.2.3 §18.6，整体替换语义）。
 *
 * <p>提交的家具列表为该 Analysis Revision 下最终生效数据：带 furnitureId 的更新人工字段、
 * 缺席的软删、不带 furnitureId 的新增（source_kind=MANUAL）。自动快照字段
 * （sourceHandles/insertPath/blockName/evidence/geometry 与 annotated 尺寸等）不可由本接口修改。</p>
 */
@Data
public class FloorPlanFurnitureConfirmRequest {

    /** 目标 Analysis Revision（必填，Invariant 3：任何对象编辑必须显式指定）。 */
    @NotNull(message = "analysisRevision 不能为空")
    private Integer analysisRevision;

    /** 该 Revision 下 Furniture 对象乐观锁版本（必填，与 analysisRevision 是两个维度）。 */
    @NotNull(message = "baseFurnitureRevision 不能为空")
    private Integer baseFurnitureRevision;

    /** 确认后的家具列表（增删改整体提交）。 */
    @NotNull(message = "家具列表不能为空")
    private List<@Valid FurnitureItem> furniture;

    /**
     * 单件家具校正项。
     */
    @Data
    public static class FurnitureItem {

        /** 已识别家具 ID；为空表示人工新增。 */
        private String furnitureId;

        /** SOFA / BED / TABLE / CHAIR / CABINET / OTHER / UNKNOWN。 */
        @Size(max = 16, message = "家具类别长度不能超过 16")
        private String category;

        @Size(max = 128, message = "语义标签长度不能超过 128")
        private String semanticLabel;

        /** 归属房间 ID（可空：跨房间/开放空间/目标区域外）。 */
        private String roomId;

        /** 插入点（毫米）。 */
        @Valid
        private PointItem position;

        /** 旋转角（度）。 */
        private BigDecimal rotationDeg;

        /** 占地轮廓 [[x,y],...] 闭合多边形，毫米。 */
        private List<List<Double>> footprintPolygon;

        /** 生效尺寸（毫米，非负）。 */
        private Integer effectiveWidthMm;
        private Integer effectiveDepthMm;
        private Integer heightMm;

        private Integer sortOrder;

        /** 人工拆分/合并来源对象 ID 列表（记 SPLIT/MERGE Diff）。 */
        private List<String> derivedFromIds;
    }

    /**
     * 点坐标（毫米）。
     */
    @Data
    public static class PointItem {
        private Double x;
        private Double y;
    }
}
