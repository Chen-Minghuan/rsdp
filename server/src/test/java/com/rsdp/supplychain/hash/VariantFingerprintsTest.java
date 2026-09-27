package com.rsdp.supplychain.hash;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link VariantFingerprints} 单元测试（coalesce 与 IdentityValue 类型化取值协议）。
 * 原 v1/v2 指纹哈希用例随 variant_fingerprint 表一并移除（2026-09-24 评审决策）。
 */
class VariantFingerprintsTest {

    @Test
    void coalesce_shouldMirrorPostgresCoalesce() {
        assertEquals("C", VariantFingerprints.coalesce("C", "t"));
        assertEquals("t", VariantFingerprints.coalesce(null, "t"));
        assertEquals("", VariantFingerprints.coalesce(null, null));
        // 空串 code 不是 null，按 COALESCE 语义取空串
        assertEquals("", VariantFingerprints.coalesce("", "t"));
    }

    @Test
    void v2_shouldSerializeTypedDiscriminators() {
        assertEquals("CODE:BEIGE",
            VariantFingerprints.IdentityValue.code("BEIGE").serialize());
        assertEquals("TEXT:茶色",
            VariantFingerprints.IdentityValue.text("茶色").serialize());
        assertEquals("MM:3890x1640x720",
            VariantFingerprints.IdentityValue.mm(
                new BigDecimal("3890"), new BigDecimal("1640"), new BigDecimal("720")).serialize());
        assertEquals("MM:3890x1640x720",
            VariantFingerprints.IdentityValue.mm(
                new BigDecimal("3890.0"), new BigDecimal("1640.00"), new BigDecimal("720")).serialize());
        assertEquals("ABSENT", VariantFingerprints.IdentityValue.absent().serialize());
        assertEquals("NUM:1850",
            VariantFingerprints.IdentityValue.num(new BigDecimal("1850.0")).serialize());
    }

    @Test
    void v2_shouldApplyFixedPrecedence() {
        // 标准码 → 归一原文 → ABSENT
        assertEquals(VariantFingerprints.IdentityValue.Kind.CODE,
            VariantFingerprints.IdentityValue.of("BEIGE", "米色").kind());
        assertEquals(VariantFingerprints.IdentityValue.Kind.TEXT,
            VariantFingerprints.IdentityValue.of(null, "米色").kind());
        assertEquals(VariantFingerprints.IdentityValue.Kind.ABSENT,
            VariantFingerprints.IdentityValue.of(null, null).kind());
        assertEquals(VariantFingerprints.IdentityValue.Kind.ABSENT,
            VariantFingerprints.IdentityValue.of("", "").kind());
    }

    @Test
    void v2_shouldRejectEmptyTextOrCode() {
        assertThrows(IllegalArgumentException.class,
            () -> VariantFingerprints.IdentityValue.code(""));
        assertThrows(IllegalArgumentException.class,
            () -> VariantFingerprints.IdentityValue.text(null));
    }




}
