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

    // ---------- R2 沙发墙朝向（v3.0 §8 P1） ----------

    @Test
    void r2_depthSofaWall_shouldUseDepthAsWallLength() {
        // 4200×5000 = 21㎡（大客厅，无分档上限）
        // 默认 width：min(4200×0.75, 4200-600) = 3150；depth 朝向：min(5000×0.75, 5000-600) = 3750
        assertThat(RoomDimensionRules.sofaMaxLengthMm(4200, 5000, RoomDimensionRules.SOFA_WALL_DEPTH, rules))
            .isEqualTo(3750);

        // 3700 的沙发在默认 width 朝向下被 R2 剔除，depth 朝向下保留
        FilterResult widthResult = RoomDimensionRules.filter(4200, 5000, List.of(
            sofa("SF-LONG", 3700, 1000, false)), rules);
        assertThat(widthResult.candidatesByCategory().get("SF")).isEmpty();

        FilterResult depthResult = RoomDimensionRules.filter(4200, 5000,
            RoomDimensionRules.SOFA_WALL_DEPTH, List.of(
                sofa("SF-LONG", 3700, 1000, false)), rules);
        assertThat(depthResult.candidatesByCategory().get("SF"))
            .extracting(CandidateProduct::rspuId).containsExactly("SF-LONG");
    }

    @Test
    void r2_depthSofaWall_tvCabinetCapShouldSwapDirection() {
        // 4000×5000：电视柜上限默认 width=4000×0.8=3200；depth 朝向=5000×0.8=4000
        FilterResult widthResult = RoomDimensionRules.filter(4000, 5000, List.of(
            sofa("SF-1", 2000, 1000, false),
            cand("FC-LONG", "FC", 3500, 400)), rules);
        assertThat(widthResult.candidatesByCategory().get("FC")).isEmpty();

        FilterResult depthResult = RoomDimensionRules.filter(4000, 5000,
            RoomDimensionRules.SOFA_WALL_DEPTH, List.of(
                sofa("SF-1", 2000, 1000, false),
                cand("FC-LONG", "FC", 3500, 400)), rules);
        assertThat(depthResult.candidatesByCategory().get("FC"))
            .extracting(CandidateProduct::rspuId).containsExactly("FC-LONG");
    }

    @Test
    void r3_depthSofaWall_chainShouldRunAlongWidth() {
        // 2800×5000：全链 1000+350+600+600+400 = 2950
        // 默认 width 朝向链沿进深 5000 核算 → 全链成立；depth 朝向链沿开间 2800 核算 → 去电视柜
        FilterResult widthResult = RoomDimensionRules.filter(2800, 5000, List.of(
            sofa("SF-1", 2000, 1000, false),
            cand("TB-1", "TB", 1200, 600),
            cand("FC-1", "FC", 2000, 400)), rules);
        assertThat(widthResult.candidatesByCategory().get("FC")).hasSize(1);
        assertThat(widthResult.degradations()).isEmpty();

        FilterResult depthResult = RoomDimensionRules.filter(2800, 5000,
            RoomDimensionRules.SOFA_WALL_DEPTH, List.of(
                sofa("SF-1", 2000, 1000, false),
                cand("TB-1", "TB", 1200, 600),
                cand("FC-1", "FC", 2000, 400)), rules);
        assertThat(depthResult.candidatesByCategory().get("FC")).isEmpty();
        assertThat(depthResult.degradations()).anyMatch(d -> d.contains("电视柜"));
    }

    @Test
    void sofaWall_nullOrDefault_shouldBehaveExactlyLikeWidth() {
        // 无朝向（null）与显式 width 行为完全一致（保持无朝向时行为不变）
        List<CandidateProduct> candidates = List.of(
            sofa("SF-1", 2200, 1000, false),
            cand("TB-1", "TB", 1200, 600),
            cand("FC-1", "FC", 2000, 400));
        FilterResult defaultResult = RoomDimensionRules.filter(4200, 3800, candidates, rules);
        FilterResult nullResult = RoomDimensionRules.filter(4200, 3800, null, candidates, rules);
        FilterResult widthResult = RoomDimensionRules.filter(4200, 3800,
            RoomDimensionRules.SOFA_WALL_WIDTH, candidates, rules);

        assertThat(nullResult.candidatesByCategory().get("SF"))
            .isEqualTo(defaultResult.candidatesByCategory().get("SF"));
        assertThat(widthResult.candidatesByCategory().get("SF"))
            .isEqualTo(defaultResult.candidatesByCategory().get("SF"));
        assertThat(nullResult.degradations()).isEqualTo(defaultResult.degradations());
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

    // ---------- 多空间模板（v3.0 §8 P2） ----------

    @Test
    void templateForRoomType_shouldMapDictCodesAndRejectUnsupported() {
        assertThat(RoomDimensionRules.templateForRoomType("LIVING_ROOM"))
            .isSameAs(RoomDimensionRules.LIVING_TEMPLATE);
        assertThat(RoomDimensionRules.templateForRoomType("DINING_ROOM"))
            .isSameAs(RoomDimensionRules.DINING_TEMPLATE);
        assertThat(RoomDimensionRules.templateForRoomType("BEDROOM"))
            .isSameAs(RoomDimensionRules.BEDROOM_TEMPLATE);
        assertThat(RoomDimensionRules.templateForRoomType("KITCHEN")).isNull();
        assertThat(RoomDimensionRules.templateForRoomType(null)).isNull();
    }

    @Test
    void diningTemplate_shouldBeDtRequiredFsOptional4() {
        assertThat(RoomDimensionRules.DINING_TEMPLATE.requiredCategories()).containsExactly("DT");
        assertThat(RoomDimensionRules.DINING_TEMPLATE.maxPerCategory())
            .containsEntry("DT", 1).containsEntry("FS", 4);
        assertThat(RoomDimensionRules.DINING_TEMPLATE.sceneCode()).isNull();
    }

    @Test
    void bedroomTemplate_shouldBeBdRequiredFcOptional2() {
        assertThat(RoomDimensionRules.BEDROOM_TEMPLATE.requiredCategories()).containsExactly("BD");
        assertThat(RoomDimensionRules.BEDROOM_TEMPLATE.maxPerCategory())
            .containsEntry("BD", 1).containsEntry("FC", 2);
        assertThat(RoomDimensionRules.BEDROOM_TEMPLATE.sceneCode()).isEqualTo("BEDROOM");
    }

    @Test
    void filterDining_shouldCapTableLengthByWallRatioAndWalkway() {
        // 开间 3000：餐桌上限 min(3000×0.75, 3000-600) = 2250
        FilterResult result = RoomDimensionRules.filterDining(3000, 2800, List.of(
            cand("DT-OVER", "DT", 2400, 1200),
            cand("DT-FIT", "DT", 2200, 1100),
            cand("FS-1", "FS", 500, 500),
            cand("SF-1", "SF", 2000, 1000) // 非餐厅模板品类
        ), rules);

        assertThat(result.candidatesByCategory().get("DT"))
            .extracting(CandidateProduct::rspuId).containsExactly("DT-FIT");
        // 餐椅绕桌摆放不做尺寸约束
        assertThat(result.candidatesByCategory().get("FS"))
            .extracting(CandidateProduct::rspuId).containsExactly("FS-1");
        // SF 不属于餐厅模板（R4 品类过滤）
        assertThat(result.flatCandidates())
            .extracting(CandidateProduct::rspuId).containsExactlyInAnyOrder("DT-FIT", "FS-1");
    }

    @Test
    void filterDining_shouldApplyR5QualityFilter() {
        CandidateProduct noDims = new CandidateProduct("DT-NODIM", "DT", null, null,
            false, true, true, 0.0, LocalDateTime.now());
        CandidateProduct noRsku = new CandidateProduct("DT-NORSKU", "DT", 2000, 1000,
            false, true, false, 0.0, LocalDateTime.now());

        FilterResult result = RoomDimensionRules.filterDining(3000, 2800,
            List.of(cand("DT-1", "DT", 2000, 1000), noDims, noRsku), rules);

        assertThat(result.candidatesByCategory().get("DT"))
            .extracting(CandidateProduct::rspuId).containsExactly("DT-1");
    }

    @Test
    void filterBedroom_shouldCapBedLengthByWidthMinusWalkway() {
        // 开间 3300：床长上限 3300-600 = 2700
        FilterResult result = RoomDimensionRules.filterBedroom(3300, 3000, List.of(
            cand("BD-OVER", "BD", 2800, 2000),
            cand("BD-FIT", "BD", 2200, 1900),
            cand("FC-1", "FC", 800, 400)
        ), rules);

        assertThat(result.candidatesByCategory().get("BD"))
            .extracting(CandidateProduct::rspuId).containsExactly("BD-FIT");
        // 柜类可靠其他墙摆放，不做尺寸约束
        assertThat(result.candidatesByCategory().get("FC"))
            .extracting(CandidateProduct::rspuId).containsExactly("FC-1");
    }

    @Test
    void filterBedroom_shouldRequireBedroomScene() {
        // R4：卧室模板有场景码 BEDROOM，未命中场景的床被剔除
        CandidateProduct noScene = new CandidateProduct("BD-NOSCENE", "BD", 2200, 1900,
            false, false, true, 0.0, LocalDateTime.now());

        FilterResult result = RoomDimensionRules.filterBedroom(3300, 3000,
            List.of(cand("BD-1", "BD", 2200, 1900), noScene), rules);

        assertThat(result.candidatesByCategory().get("BD"))
            .extracting(CandidateProduct::rspuId).containsExactly("BD-1");
    }
}
