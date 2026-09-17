package com.rsdp.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * 户型图自动标定建议（户型图优化二期）。
 *
 * <p>用「高置信 OCR 尺寸 + bbox」反推全图比例（mm/px），双端统一响应结构：
 * 管理端随 {@code GET /api/v1/floor-plan/{analysisId}} 返回，
 * 官网随 analyze 响应返回。字段名即前端契约，不得随意改动。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ScaleSuggestionResponse {

    /**
     * 建议状态：auto=估计聚类一致可直接采用 / candidates=各房间估计互不一致返回候选 /
     * null=无可建议（无可解析房间或比例关系不足）。
     */
    private String status;

    /** status=auto 时的建议比例（簇中位数，mm/px）；其余状态为 null。 */
    private BigDecimal mmPerPx;

    /** status=auto 时簇内首个房间的标注名；其余状态为 null。 */
    private String basisLabel;

    /** status=candidates 时各房间独立估计（宽深两估计取均值）；其余状态为 null。 */
    private List<Candidate> candidates;

    /**
     * status=auto 时被主簇剔除的离群估计（按 mmPerPx 升序，可为空数组）；
     * status=candidates/null 时为 null。前端据此提示「另有 N 个标注不一致已忽略」。
     */
    private List<Outlier> outliers;

    /**
     * 单个房间的独立比例估计（status=candidates 时返回）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Candidate {

        /** 房间标注名。 */
        private String label;

        /** 该房间的独立比例估计（宽深两估计均值，mm/px）。 */
        private BigDecimal mmPerPx;

        /** 尺寸标注原文（估计依据）。 */
        private String dimensionText;

        /** 是否与任何其他候选一致（相对偏差 ≤5%）：true 表示该候选更可信。 */
        private Boolean agreed;
    }

    /**
     * 被主簇剔除的离群比例估计（status=auto 时返回）。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Outlier {

        /** 来源房间标注名。 */
        private String label;

        /** 该离群估计值（mm/px）。 */
        private BigDecimal mmPerPx;

        /** 尺寸标注原文（估计依据）。 */
        private String dimensionText;
    }
}
