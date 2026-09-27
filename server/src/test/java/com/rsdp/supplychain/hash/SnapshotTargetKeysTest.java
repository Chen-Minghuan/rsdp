package com.rsdp.supplychain.hash;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SnapshotTargetKeys} 单元测试（§24.1 Issue Target Protocol，
 * §85 收口第 17 条 / §77.3：一行展开多 Variant / Quote 时 variantKey / quoteKey 稳定且唯一）。
 */
class SnapshotTargetKeysTest {

    private Map<String, VariantFingerprints.IdentityValue> sampleIdentity() {
        Map<String, VariantFingerprints.IdentityValue> fields = new LinkedHashMap<>();
        fields.put("size", VariantFingerprints.IdentityValue.mm(
            new BigDecimal("3890"), new BigDecimal("1640"), new BigDecimal("720")));
        fields.put("color", VariantFingerprints.IdentityValue.text("茶色"));
        fields.put("material", VariantFingerprints.IdentityValue.absent());
        return fields;
    }

    @Test
    void variantKey_shouldHavePrefixAndDeterministicHash() {
        String key = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        assertTrue(key.startsWith(SnapshotTargetKeys.VARIANT_PREFIX));
        assertEquals("VAR:".length() + 64, key.length());
        assertEquals(key, SnapshotTargetKeys.variantKeyV2(2, sampleIdentity()));
    }

    @Test
    void variantKey_shouldBeFieldOrderIndependent() {
        Map<String, VariantFingerprints.IdentityValue> reversed = new LinkedHashMap<>();
        reversed.put("material", VariantFingerprints.IdentityValue.absent());
        reversed.put("color", VariantFingerprints.IdentityValue.text("茶色"));
        reversed.put("size", VariantFingerprints.IdentityValue.mm(
            new BigDecimal("3890"), new BigDecimal("1640"), new BigDecimal("720")));
        assertEquals(SnapshotTargetKeys.variantKeyV2(2, sampleIdentity()),
            SnapshotTargetKeys.variantKeyV2(2, reversed));
    }

    @Test
    void variantKey_shouldDistinguishIdentityValues() {
        String base = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        Map<String, VariantFingerprints.IdentityValue> otherColor = sampleIdentity();
        otherColor.put("color", VariantFingerprints.IdentityValue.text("米色"));
        assertNotEquals(base, SnapshotTargetKeys.variantKeyV2(2, otherColor));

        Map<String, VariantFingerprints.IdentityValue> otherSize = sampleIdentity();
        otherSize.put("size", VariantFingerprints.IdentityValue.mm(
            new BigDecimal("3890"), new BigDecimal("1640"), new BigDecimal("730")));
        assertNotEquals(base, SnapshotTargetKeys.variantKeyV2(2, otherSize));

        // 身份版本不同 → 键不同
        assertNotEquals(base, SnapshotTargetKeys.variantKeyV2(3, sampleIdentity()));
    }

    @Test
    void variantKeyV1_shouldKeepLegacyCodeTextEquivalence() {
        // v1 是逻辑键层的 Legacy 口径：code=RED 与 text=RED 得到同一 variantKey
        assertEquals(
            SnapshotTargetKeys.variantKeyV1(null, null, "RED", null, null, null),
            SnapshotTargetKeys.variantKeyV1(null, null, null, "RED", null, null));
    }

    @Test
    void variantKeyV2_shouldRejectLegacyVersion() {
        assertThrows(IllegalArgumentException.class,
            () -> SnapshotTargetKeys.variantKeyV2(1, sampleIdentity()));
    }

    @Test
    void quoteKey_shouldBeStableForSameLogicalQuote() {
        // §24.1：同一逻辑 Quote 重复出现复用同一 quoteKey，不掺入源坐标
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        String first = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01");
        String second = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01");
        assertEquals(first, second);
        assertTrue(first.startsWith(SnapshotTargetKeys.QUOTE_PREFIX));
        assertEquals("QUOTE:".length() + 64, first.length());
    }

    @Test
    void quoteKey_shouldChangeWithAnyMaterialField() {
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        String base = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01");
        assertNotEquals(base, SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-02", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01"));
        assertNotEquals(base, SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "USD", "PER_PIECE", "2026-09-01"));
        assertNotEquals(base, SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_SET", "2026-09-01"));
        assertNotEquals(base, SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-02"));
        // quoteKey 必须引用同 Snapshot 内存在的 variantKey：variant 变化 → 键变化
        Map<String, VariantFingerprints.IdentityValue> otherIdentity = sampleIdentity();
        otherIdentity.put("color", VariantFingerprints.IdentityValue.text("米色"));
        assertNotEquals(base, SnapshotTargetKeys.quoteKey(
            SnapshotTargetKeys.variantKeyV2(2, otherIdentity),
            "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01"));
    }

    @Test
    void quoteKey_shouldTreatNullEffectiveFromAsExplicitDistinctKey() {
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        String dated = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01");
        String undated = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", null);
        assertNotEquals(dated, undated);
    }

    @Test
    void quoteKey_shouldRejectBlankRequiredFields() {
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        assertThrows(IllegalArgumentException.class, () -> SnapshotTargetKeys.quoteKey(
            null, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", null));
        assertThrows(IllegalArgumentException.class, () -> SnapshotTargetKeys.quoteKey(
            variantKey, "", "SUPCONF-9", "CNY", "PER_PIECE", null));
        assertThrows(IllegalArgumentException.class, () -> SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", null, "CNY", "PER_PIECE", null));
    }

    @Test
    void targetRskuKey_shouldIgnoreQuoteBasisFields() {
        // §24.1：targetRskuKey 只含 variant/factory/supplierConfiguration 三元组；
        // 同一 RSKU 上基础口径不同的 Quote 共享同一 targetRskuKey（供上层检测多 current quote）
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        String target = SnapshotTargetKeys.targetRskuKey(variantKey, "FAC-01", "SUPCONF-9");
        assertTrue(target.startsWith(SnapshotTargetKeys.RSKU_TARGET_PREFIX));

        String quoteCny = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "CNY", "PER_PIECE", "2026-09-01");
        String quoteUsd = SnapshotTargetKeys.quoteKey(
            variantKey, "FAC-01", "SUPCONF-9", "USD", "PER_PIECE", "2026-09-01");
        assertNotEquals(quoteCny, quoteUsd);
        assertEquals(target, SnapshotTargetKeys.targetRskuKey(variantKey, "FAC-01", "SUPCONF-9"));

        assertNotEquals(target, SnapshotTargetKeys.targetRskuKey(variantKey, "FAC-02", "SUPCONF-9"));
    }

    @Test
    void fieldTargetKey_shouldJoinOwnerAndJsonPointer() {
        String variantKey = SnapshotTargetKeys.variantKeyV2(2, sampleIdentity());
        assertEquals(variantKey + "#/dimensions/widthMm",
            SnapshotTargetKeys.fieldTargetKey(variantKey, "/dimensions/widthMm"));
        assertEquals("GROUP:G-1#/finalCategoryCode",
            SnapshotTargetKeys.fieldTargetKey("GROUP:G-1", "/finalCategoryCode"));
    }

    @Test
    void fieldTargetKey_shouldValidateOwnerPrefixAndPointer() {
        assertThrows(IllegalArgumentException.class,
            () -> SnapshotTargetKeys.fieldTargetKey("ROW:3", "/a"));
        assertThrows(IllegalArgumentException.class,
            () -> SnapshotTargetKeys.fieldTargetKey("GROUP:G-1", "dimensions"));
    }
}
