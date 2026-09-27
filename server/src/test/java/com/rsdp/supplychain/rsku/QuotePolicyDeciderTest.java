package com.rsdp.supplychain.rsku;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link QuotePolicyDecider} 单元测试（设计文档 v3.3 §47.1.5 quotePolicy 条件表全覆盖，
 * §85 收口第 22 条：日期/同值条件表 9 行 + 基础口径冲突 + keep_existing / force_replace）。
 */
class QuotePolicyDeciderTest {

    private static final LocalDate D1 = LocalDate.of(2026, 1, 10);
    private static final LocalDate D2 = LocalDate.of(2026, 2, 10);

    private QuotePolicyDecider.Outcome decide(LocalDate incoming, LocalDate existing, boolean valueSame) {
        return QuotePolicyDecider.decide("replace_if_newer", incoming, existing, valueSame, true);
    }

    // ------------------------------------------------------------ 条件表 9 行

    @Test
    void newerDate_valueChanged_updates() {
        QuotePolicyDecider.Outcome o = decide(D2, D1, false);
        assertEquals(QuotePolicyDecider.Decision.UPDATE, o.decision());
        assertNull(o.issueCode());
        assertTrue(o.allowsWrite());
    }

    @Test
    void newerDate_valueSame_updatesValidityOnly() {
        QuotePolicyDecider.Outcome o = decide(D2, D1, true);
        assertEquals(QuotePolicyDecider.Decision.UPDATE_VALIDITY_ONLY, o.decision());
        assertNull(o.issueCode());
        assertTrue(o.allowsWrite());
    }

    @Test
    void olderDate_keepsStaleIgnored_regardlessOfValue() {
        for (boolean valueSame : new boolean[]{true, false}) {
            QuotePolicyDecider.Outcome o = decide(D1, D2, valueSame);
            assertEquals(QuotePolicyDecider.Decision.KEEP_STALE_IGNORED, o.decision());
            assertEquals("STALE_QUOTE_IGNORED", o.issueCode());
            assertFalse(o.allowsWrite());
            assertFalse(o.isConflict());
        }
    }

    @Test
    void sameDate_valueSame_noOp() {
        QuotePolicyDecider.Outcome o = decide(D1, D1, true);
        assertEquals(QuotePolicyDecider.Decision.NO_OP, o.decision());
        assertNull(o.issueCode());
        assertFalse(o.allowsWrite());
    }

    @Test
    void sameDate_valueChanged_conflict() {
        QuotePolicyDecider.Outcome o = decide(D1, D1, false);
        assertEquals(QuotePolicyDecider.Decision.CONFLICT_SAME_DATE, o.decision());
        assertEquals("QUOTE_SAME_DATE_CONFLICT", o.issueCode());
        assertTrue(o.isConflict());
        assertFalse(o.allowsWrite());
    }

    @Test
    void incomingDated_existingUndated_updates() {
        // 有日期事实优先于无日期事实（不看值是否相同）
        for (boolean valueSame : new boolean[]{true, false}) {
            QuotePolicyDecider.Outcome o = decide(D1, null, valueSame);
            assertEquals(QuotePolicyDecider.Decision.UPDATE, o.decision());
            assertNull(o.issueCode());
            assertTrue(o.allowsWrite());
        }
    }

    @Test
    void incomingUndated_existingDated_valueSame_noOp() {
        QuotePolicyDecider.Outcome o = decide(null, D1, true);
        assertEquals(QuotePolicyDecider.Decision.NO_OP, o.decision());
        assertNull(o.issueCode());
    }

    @Test
    void incomingUndated_existingDated_valueChanged_conflict() {
        QuotePolicyDecider.Outcome o = decide(null, D1, false);
        assertEquals(QuotePolicyDecider.Decision.CONFLICT_DATE_MISSING, o.decision());
        assertEquals("QUOTE_DATE_MISSING_CONFLICT", o.issueCode());
        assertTrue(o.isConflict());
    }

    @Test
    void bothUndated_valueSame_noOp() {
        QuotePolicyDecider.Outcome o = decide(null, null, true);
        assertEquals(QuotePolicyDecider.Decision.NO_OP, o.decision());
        assertNull(o.issueCode());
    }

    @Test
    void bothUndated_valueChanged_conflict() {
        QuotePolicyDecider.Outcome o = decide(null, null, false);
        assertEquals(QuotePolicyDecider.Decision.CONFLICT_UNDATED, o.decision());
        assertEquals("QUOTE_UNDATED_CONFLICT", o.issueCode());
        assertTrue(o.isConflict());
    }

    // ------------------------------------------------------------ 基础口径冲突（不走日期表）

    @Test
    void basisMismatch_conflictsBeforeDateTable() {
        // 即使 incoming 日期严格更晚，基础口径不同也直接冲突
        QuotePolicyDecider.Outcome o = QuotePolicyDecider.decide("replace_if_newer", D2, D1, false, false);
        assertEquals(QuotePolicyDecider.Decision.CONFLICT_QUOTE_BASIS, o.decision());
        assertEquals("QUOTE_BASIS_CONFLICT", o.issueCode());
        assertTrue(o.isConflict());
        assertFalse(o.allowsWrite());
    }

    // ------------------------------------------------------------ keep_existing / force_replace

    @Test
    void keepExisting_skips_regardlessOfDatesAndValues() {
        for (LocalDate incoming : new LocalDate[]{null, D1, D2}) {
            for (LocalDate existing : new LocalDate[]{null, D1}) {
                for (boolean valueSame : new boolean[]{true, false}) {
                    QuotePolicyDecider.Outcome o = QuotePolicyDecider.decide(
                        "keep_existing", incoming, existing, valueSame, true);
                    assertEquals(QuotePolicyDecider.Decision.KEEP_EXISTING_SKIPPED, o.decision());
                    assertNull(o.issueCode());
                    assertFalse(o.allowsWrite());
                }
            }
        }
    }

    @Test
    void forceReplace_overridesEverything_includingBasisMismatch() {
        // force_replace 连基础口径冲突也覆盖（权限/reason 校验在 Service 层）
        QuotePolicyDecider.Outcome o = QuotePolicyDecider.decide("force_replace", null, D1, false, false);
        assertEquals(QuotePolicyDecider.Decision.FORCE_REPLACE, o.decision());
        assertNull(o.issueCode());
        assertTrue(o.allowsWrite());
    }

    @Test
    void keepExisting_ignoresBasisMismatch() {
        QuotePolicyDecider.Outcome o = QuotePolicyDecider.decide("keep_existing", D2, D1, false, false);
        assertEquals(QuotePolicyDecider.Decision.KEEP_EXISTING_SKIPPED, o.decision());
    }
}
