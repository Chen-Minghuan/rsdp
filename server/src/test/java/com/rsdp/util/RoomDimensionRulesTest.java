package com.rsdp.util;

import com.rsdp.config.properties.FloorPlanRulesProperties;
import com.rsdp.util.RoomDimensionRules.CandidateProduct;
import com.rsdp.util.RoomDimensionRules.FilterResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RoomDimensionRules} 单元测试（户型图链路 v3.0 §5.2 / §9）。
 *
 * <p>覆盖：R1 面积分档三档、R2 沙发长度上限、R3 进深链式校验（含三级降级）、
 * R4 场景/品类过滤、R5 数据质量（无尺寸/无报价剔除）、每品类候选上限。</p>
 */
class RoomDimensionRulesTest {

    private final FloorPlanRulesProperties rules = new FloorPlanRulesProperties();

    private CandidateProduct cand(String id, String category, Integer widthMm, Integer depthMm) {
        return new CandidateProduct(id, category, widthMm, depthMm, false, true, true, 0.0,
            LocalDateTime.now());
    }

    private CandidateProduct sofa(String id, int widthMm, int depthMm, boolean lType) {
        return new CandidateProduct(id, "SF", widthMm, depthMm, lType, true, true, 0.0,
            LocalDateTime.now());
    }

    // ---------- R1 面积分档 ----------

    @Test
    void r1_smallLiving_shouldCapSofaAt2200AndExcludeLType() {
        // 3000×3500 = 10.5㎡ < 12㎡：沙发 ≤2200，不推 L 型
        FilterResult result = RoomDimensionRules.filter(3000, 3500, List.of(
            sofa("SF-OVER", 2300, 1000, false),
            sofa("SF-FIT", 2100, 1000, false),
            sofa("SF-L", 2000, 1000, true)
        ), rules);

        List<CandidateProduct> sofas = result.candidatesByCategory().get("SF");
        assertThat(sofas).extracting(CandidateProduct::rspuId).containsExactly("SF-FIT");
    }

    @Test
    void r1_midLiving_shouldCapSofaAt2400AndExcludeLType() {
        // 4200×3800 = 15.96㎡（12~20㎡）：三人位为主 ≤2400，L 型不推
        FilterResult result = RoomDimensionRules.filter(4200, 3800, List.of(
            sofa("SF-OVER", 2500, 1000, false),
            sofa("SF-FIT", 2400, 1000, false),
            sofa("SF-L", 2300, 1000, true)
        ), rules);

        List<CandidateProduct> sofas = result.candidatesByCategory().get("SF");
        assertThat(sofas).extracting(CandidateProduct::rspuId).containsExactly("SF-FIT");
    }

    @Test
    void r1_largeLiving_shouldAllowLTypeAndBigSofa() {
        // 5500×4500 = 24.75㎡ > 20㎡：可 L 型/组合，分档不设上限（仅受 R2 约束）
        FilterResult result = RoomDimensionRules.filter(5500, 4500, List.of(
            sofa("SF-L", 3200, 1100, true),
            sofa("SF-FIT", 2800, 1000, false)
        ), rules);

        List<CandidateProduct> sofas = result.candidatesByCategory().get("SF");
        assertThat(sofas).extracting(CandidateProduct::rspuId)
            .containsExactlyInAnyOrder("SF-L", "SF-FIT");
    }

    // ---------- R2 沙发长度约束 ----------

    @Test
    void r2_sofaWidth_shouldNotExceedWallRatio() {
        // 5000×5000 = 25㎡（大客厅，无分档上限）：min(5000×0.75, 5000-600) = 3750
        assertThat(RoomDimensionRules.sofaMaxLengthMm(5000, 5000, rules)).isEqualTo(3750);

        FilterResult result = RoomDimensionRules.filter(5000, 5000, List.of(
            sofa("SF-OVER", 3800, 1000, false),
            sofa("SF-FIT", 3700, 1000, false)
        ), rules);

        assertThat(result.candidatesByCategory().get("SF"))
            .extracting(CandidateProduct::rspuId).containsExactly("SF-FIT");
    }

    @Test
    void r2_sofaWidth_walkwayReservationShouldBindOnNarrowRoom() {
        // 2000×5000 = 10㎡（小客厅）：min(2200, 2000×0.75=1500, 2000-600=1400) = 1400，过道预留生效
        assertThat(RoomDimensionRules.sofaMaxLengthMm(2000, 5000, rules)).isEqualTo(1400);
    }

    // ---------- R3 进深链式校验 ----------

    @Test
    void r3_fullChainFits_shouldKeepAllCategories() {
        // 1000 + 350 + 600 + 600 + 400 = 2950 ≤ 3800，全链成立
        FilterResult result = RoomDimensionRules.filter(4200, 3800, List.of(
            sofa("SF-1", 2200, 1000, false),
            cand("TB-1", "TB", 1200, 600),
            cand("FC-1", "FC", 2000, 400)
        ), rules);

        assertThat(result.candidatesByCategory().get("SF")).hasSize(1);
        assertThat(result.candidatesByCategory().get("TB")).hasSize(1);
        assertThat(result.candidatesByCategory().get("FC")).hasSize(1);
        assertThat(result.degradations()).isEmpty();
    }

    @Test
    void r3_depthShort_shouldDegradeByDroppingTvCabinet() {
        // 1000 + 350 + 600 + 600 + 400 = 2950 > 2900 → 去电视柜；
        // 去柜后 1000 + 350 + 600 = 1950 ≤ 2900，沙发+茶几保留
        FilterResult result = RoomDimensionRules.filter(4200, 2900, List.of(
            sofa("SF-1", 2200, 1000, false),
            cand("TB-1", "TB", 1200, 600),
            cand("FC-1", "FC", 2000, 400)
        ), rules);

        assertThat(result.candidatesByCategory().get("SF")).hasSize(1);
        assertThat(result.candidatesByCategory().get("TB")).hasSize(1);
        assertThat(result.candidatesByCategory().get("FC")).isEmpty();
        assertThat(result.degradations()).anyMatch(d -> d.contains("电视柜"));
    }

    @Test
    void r3_depthShortWithoutTvCabinet_shouldDegradeToNarrowTeaTable() {
        // 无电视柜；1000 + 350 + 800 = 2150 > 2100 → 换窄茶几：茶几深 ≤ 2100-1000-350 = 750
        FilterResult result = RoomDimensionRules.filter(4200, 2100, List.of(
            sofa("SF-1", 2000, 1000, false),
            cand("TB-WIDE", "TB", 1200, 800),
            cand("TB-NARROW", "TB", 1100, 700)
        ), rules);

        assertThat(result.candidatesByCategory().get("TB"))
            .extracting(CandidateProduct::rspuId).containsExactly("TB-NARROW");
        assertThat(result.degradations()).anyMatch(d -> d.contains("茶几"));
    }

    @Test
    void r3_depthTooShort_shouldDegradeToSmallerSofa() {
        // 无茶几无电视柜：沙发深 2000 > 进深 1900 → 换小沙发
        FilterResult result = RoomDimensionRules.filter(2000, 1900, List.of(
            sofa("SF-DEEP", 1300, 2000, false),
            sofa("SF-SHALLOW", 1300, 1500, false)
        ), rules);

        assertThat(result.candidatesByCategory().get("SF"))
            .extracting(CandidateProduct::rspuId).containsExactly("SF-SHALLOW");
        assertThat(result.degradations()).anyMatch(d -> d.contains("沙发"));
    }

    // ---------- R4 品类过滤 ----------

    @Test
    void r4_shouldFilterNonLivingSceneAndOffTemplateCategory() {
        CandidateProduct noLiving = new CandidateProduct("SF-NOSCENE", "SF", 2000, 1000,
            false, false, true, 0.0, LocalDateTime.now());
        CandidateProduct offTemplate = cand("BD-1", "BD", 1800, 2000);

        FilterResult result = RoomDimensionRules.filter(4200, 3800,
            List.of(sofa("SF-1", 2200, 1000, false), noLiving, offTemplate), rules);

        assertThat(result.flatCandidates())
            .extracting(CandidateProduct::rspuId).containsExactly("SF-1");
    }

    // ---------- R5 数据质量 ----------

    @Test
    void r5_shouldFilterUnparsableDimensionsAndNoQuotation() {
        CandidateProduct noDims = new CandidateProduct("SF-NODIM", "SF", null, null,
            false, true, true, 0.0, LocalDateTime.now());
        CandidateProduct noRsku = new CandidateProduct("SF-NORSKU", "SF", 2000, 1000,
            false, true, false, 0.0, LocalDateTime.now());

        FilterResult result = RoomDimensionRules.filter(4200, 3800,
            List.of(sofa("SF-1", 2200, 1000, false), noDims, noRsku), rules);

        assertThat(result.candidatesByCategory().get("SF"))
            .extracting(CandidateProduct::rspuId).containsExactly("SF-1");
    }

    // ---------- 候选上限与排序 ----------

    @Test
    void filter_shouldCapPerCategoryAt6AndSortByStyleScoreDesc() {
        List<CandidateProduct> candidates = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            candidates.add(new CandidateProduct("SF-" + i, "SF", 2000, 1000,
                false, true, true, i * 10.0, LocalDateTime.now().minusDays(i)));
        }

        FilterResult result = RoomDimensionRules.filter(4200, 3800, candidates, rules);

        List<CandidateProduct> sofas = result.candidatesByCategory().get("SF");
        assertThat(sofas).hasSize(6);
        // 风格分降序：SF-8 (80分) 排最前
        assertThat(sofas.get(0).rspuId()).isEqualTo("SF-8");
        assertThat(sofas.get(0).styleScore()).isEqualTo(80.0);
    }
}
