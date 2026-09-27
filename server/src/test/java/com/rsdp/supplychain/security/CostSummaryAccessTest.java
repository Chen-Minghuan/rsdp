package com.rsdp.supplychain.security;

import com.rsdp.entity.RspuPriceSummary;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-E 聚合成本防反推规则单元测试（设计文档 v3.3 §42.3 / §43 规则 12 / §43.1 矩阵）。
 *
 * <p>确定性输出规则矩阵：</p>
 * <ul>
 *   <li>exact 主体：任何形态都可见真实 min/max（等价精确值输出不经降级）；</li>
 *   <li>summary-only 主体：active_rsku_count &lt; 2 或 min=max → min/max 恒 null，
 *       只返回 hasFactoryQuote=true；count ≥ 2 且 min≠max → 明文区间可见；</li>
 *   <li>无成本权限主体：min/max 恒 null（布尔位是否暴露由调用方按 §43.1 决定）；</li>
 *   <li>筛选/排序预言机：仅 exact 主体可用真实成本投影（§43 规则 12）。</li>
 * </ul>
 */
class CostSummaryAccessTest {

    private final CostSummaryAccess access = new CostSummaryAccess();

    private static RspuPriceSummary summary(int count, String min, String max) {
        RspuPriceSummary s = new RspuPriceSummary();
        s.setRspuId("RSPU-1");
        s.setActiveRskuCount(count);
        s.setMinFactoryPrice(min == null ? null : new BigDecimal(min));
        s.setMaxFactoryPrice(max == null ? null : new BigDecimal(max));
        return s;
    }

    @Test
    void exactSubject_seesValuesEvenWhenDegenerate() {
        // 单 RSKU（min=max=精确成本）：exact 主体允许等价精确值输出
        CostSummaryAccess.SummaryView view = access.project(summary(1, "9500", "9500"), true, true);
        assertEquals(0, new BigDecimal("9500").compareTo(view.minFactoryPrice()));
        assertEquals(0, new BigDecimal("9500").compareTo(view.maxFactoryPrice()));
        assertEquals(Boolean.TRUE, view.hasFactoryQuote());
    }

    @Test
    void summaryOnlySubject_singleRsku_degradesToBooleanOnly() {
        CostSummaryAccess.SummaryView view = access.project(summary(1, "9500", "9500"), false, true);
        assertNull(view.minFactoryPrice());
        assertNull(view.maxFactoryPrice());
        assertEquals(Boolean.TRUE, view.hasFactoryQuote());
    }

    @Test
    void summaryOnlySubject_minEqualsMax_degradesToBooleanOnly() {
        // 两个 RSKU 同价：min=max 仍可反推精确成本 → 降级
        CostSummaryAccess.SummaryView view = access.project(summary(2, "800", "800"), false, true);
        assertNull(view.minFactoryPrice());
        assertNull(view.maxFactoryPrice());
        assertEquals(Boolean.TRUE, view.hasFactoryQuote());
    }

    @Test
    void summaryOnlySubject_genuineRange_seesPlainInterval() {
        CostSummaryAccess.SummaryView view = access.project(summary(2, "800", "1200"), false, true);
        assertEquals(0, new BigDecimal("800").compareTo(view.minFactoryPrice()));
        assertEquals(0, new BigDecimal("1200").compareTo(view.maxFactoryPrice()));
        assertEquals(Boolean.TRUE, view.hasFactoryQuote());
    }

    @Test
    void noCostPermission_neverSeesValues() {
        CostSummaryAccess.SummaryView view = access.project(summary(2, "800", "1200"), false, false);
        assertNull(view.minFactoryPrice());
        assertNull(view.maxFactoryPrice());
        assertEquals(Boolean.TRUE, view.hasFactoryQuote(), "§43.1：客户侧仅 hasFactoryQuote 布尔位");
    }

    @Test
    void noSummaryRow_reportsNoQuote() {
        CostSummaryAccess.SummaryView view = access.project(null, true, true);
        assertNull(view.minFactoryPrice());
        assertEquals(Boolean.FALSE, view.hasFactoryQuote());

        CostSummaryAccess.SummaryView zeroCount = access.project(summary(0, null, null), false, true);
        assertEquals(Boolean.FALSE, zeroCount.hasFactoryQuote());
    }

    @Test
    void filterSortOracle_onlyExactSubjectMayUseTrueCostProjection() {
        assertTrue(access.exactCostProjectionAllowed(true));
        assertFalse(access.exactCostProjectionAllowed(false));
    }
}
