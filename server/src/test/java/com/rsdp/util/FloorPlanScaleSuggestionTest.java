package com.rsdp.util;

import com.rsdp.dto.response.ScaleSuggestionResponse;
import com.rsdp.util.FloorPlanScaleSuggestion.RoomExtent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FloorPlanScaleSuggestion} 单元测试（户型图自动标定建议，含离群剔除）。
 *
 * <p>覆盖：主簇+离群剔除（离群进 outliers 且不参与中位数）、两簇平局取中位数
 * 更接近全集中位数的簇、全孤立走 candidates 且带 agreed 标记、候选一致时 agreed=true。</p>
 */
class FloorPlanScaleSuggestionTest {

    /** 测试统一用 2000×1500px 原图（估计值 = dimMm / (bbox 比例 × 对应边像素)）。 */
    private static final int IMAGE_W = 2000;
    private static final int IMAGE_H = 1500;

    @Test
    void suggest_mainClusterWithOutlier_shouldExcludeOutlierAndReport() {
        // 客厅/主卧估计均 5.0（主簇 4 个）；书房图块被拉伸 → 15.0/6.2 两个离群估计
        ScaleSuggestionResponse response = FloorPlanScaleSuggestion.suggest(List.of(
            new RoomExtent("客厅", "4000×3000", 0.4, 0.4),
            new RoomExtent("主卧", "3000×3000", 0.3, 0.4),
            new RoomExtent("书房", "2100×1860", 0.07, 0.2)), IMAGE_W, IMAGE_H);

        assertThat(response.getStatus()).isEqualTo("auto");
        assertThat(response.getMmPerPx()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.getBasisLabel()).isEqualTo("客厅");
        // 离群值按 mmPerPx 升序上报，不参与中位数
        assertThat(response.getOutliers()).hasSize(2);
        assertThat(response.getOutliers().get(0).getLabel()).isEqualTo("书房");
        assertThat(response.getOutliers().get(0).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("6.20"));
        assertThat(response.getOutliers().get(0).getDimensionText()).isEqualTo("2100×1860");
        assertThat(response.getOutliers().get(1).getLabel()).isEqualTo("书房");
        assertThat(response.getOutliers().get(1).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("15.00"));
        assertThat(response.getCandidates()).isNull();
    }

    @Test
    void suggest_noOutlier_shouldReturnEmptyOutliers() {
        // 两房间四个估计均 5.0 → 无离群，outliers 为空数组（非 null）
        ScaleSuggestionResponse response = FloorPlanScaleSuggestion.suggest(List.of(
            new RoomExtent("客厅", "4000×3000", 0.4, 0.4),
            new RoomExtent("主卧", "3000×3000", 0.3, 0.4)), IMAGE_W, IMAGE_H);

        assertThat(response.getStatus()).isEqualTo("auto");
        assertThat(response.getOutliers()).isNotNull().isEmpty();
    }

    @Test
    void suggest_tiedClusters_shouldPickMedianCloserToOverall() {
        // 三簇各 2 个估计：4.0（客房）/ 5.0（客厅）/ 5.3（书房）；全集中位数 5.0
        // → 平局取中位数最接近全集中位数的 5.0 簇（旧规则取先出现的 4.0 簇）
        ScaleSuggestionResponse response = FloorPlanScaleSuggestion.suggest(List.of(
            new RoomExtent("客房", "4000×3000", 0.5, 0.5),
            new RoomExtent("客厅", "4000×3000", 0.4, 0.4),
            new RoomExtent("书房", "4240×3180", 0.4, 0.4)), IMAGE_W, IMAGE_H);

        assertThat(response.getStatus()).isEqualTo("auto");
        assertThat(response.getMmPerPx()).isEqualByComparingTo(new BigDecimal("5.00"));
        assertThat(response.getBasisLabel()).isEqualTo("客厅");
        assertThat(response.getOutliers()).hasSize(4);
    }

    @Test
    void suggest_isolatedEstimates_shouldReturnCandidatesWithAgreedFalse() {
        // 各估计互不一致（5.0/6.0/6.5/7.0，最大最小偏差 40% >8%）→ candidates，候选互不一致
        ScaleSuggestionResponse response = FloorPlanScaleSuggestion.suggest(List.of(
            new RoomExtent("客厅", "4000×3600", 0.4, 0.4),
            new RoomExtent("主卧", "3900×4200", 0.3, 0.4)), IMAGE_W, IMAGE_H);

        assertThat(response.getStatus()).isEqualTo("candidates");
        assertThat(response.getMmPerPx()).isNull();
        assertThat(response.getOutliers()).isNull();
        assertThat(response.getCandidates()).hasSize(2);
        assertThat(response.getCandidates().get(0).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.50"));
        assertThat(response.getCandidates().get(0).getAgreed()).isFalse();
        assertThat(response.getCandidates().get(1).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("6.75"));
        assertThat(response.getCandidates().get(1).getAgreed()).isFalse();
    }

    @Test
    void suggest_candidatesWithMatchingAvg_shouldMarkAgreed() {
        // 书房 5.2/5.8、客房 4.0/7.0：四个估计两两偏差 >5% 无簇（最大最小偏差 75% >8%）
        // → candidates；两房间均值均 5.5，相对偏差 0 ≤5% → agreed=true
        ScaleSuggestionResponse response = FloorPlanScaleSuggestion.suggest(List.of(
            new RoomExtent("书房", "4160×3480", 0.4, 0.4),
            new RoomExtent("客房", "3200×4200", 0.4, 0.4)), IMAGE_W, IMAGE_H);

        assertThat(response.getStatus()).isEqualTo("candidates");
        assertThat(response.getCandidates()).hasSize(2);
        assertThat(response.getCandidates().get(0).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.50"));
        assertThat(response.getCandidates().get(0).getAgreed()).isTrue();
        assertThat(response.getCandidates().get(1).getMmPerPx())
            .isEqualByComparingTo(new BigDecimal("5.50"));
        assertThat(response.getCandidates().get(1).getAgreed()).isTrue();
    }
}
