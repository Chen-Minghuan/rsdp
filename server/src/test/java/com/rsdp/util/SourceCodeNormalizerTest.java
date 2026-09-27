package com.rsdp.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source Product Code Normalizer 测试（设计文档 v3.3 §7 规则逐条 + §77.1 normalized code 等价输入）。
 */
class SourceCodeNormalizerTest {

    // ---- §7 固定样例：四种形态全部归一为 MJ-S96（§77.1「normalized code 等价输入」） ----

    @Test
    void normalize_shouldUnifyDocumentedEquivalentForms() {
        assertEquals("MJ-S96", SourceCodeNormalizer.normalize(" MJ-S96 "));
        assertEquals("MJ-S96", SourceCodeNormalizer.normalize("mj-s96"));
        assertEquals("MJ-S96", SourceCodeNormalizer.normalize("ＭＪ－Ｓ９６"));
        assertEquals("MJ-S96", SourceCodeNormalizer.normalize("MJ - S96"));
    }

    // ---- 规则 1：trim ----

    @Test
    void normalize_shouldTrimLeadingAndTrailingWhitespace() {
        assertEquals("ABC", SourceCodeNormalizer.normalize("  ABC  "));
        assertEquals("ABC", SourceCodeNormalizer.normalize("\tABC\n"));
    }

    // ---- 规则 2：Unicode NFKC ----

    @Test
    void normalize_shouldApplyNfkcCompatibilityNormalization() {
        // 半角片假名 → 全角片假名（NFKC 兼容归一）
        assertEquals("ア", SourceCodeNormalizer.normalize("ｱ"));
        // 兼容上标 → 普通数字
        assertEquals("A2", SourceCodeNormalizer.normalize("A²"));
        // 不换行空格（U+00A0）→ 普通空格后参与 trim
        assertEquals("AB", SourceCodeNormalizer.normalize(" AB "));
    }

    // ---- 规则 3：全角转半角 ----

    @Test
    void normalize_shouldConvertFullWidthToHalfWidth() {
        assertEquals("ABC123", SourceCodeNormalizer.normalize("ＡＢＣ１２３"));
        assertEquals("A/B.C_D", SourceCodeNormalizer.normalize("Ａ／Ｂ．Ｃ＿Ｄ"));
        // 全角连字符 → 半角 -
        assertEquals("A-B", SourceCodeNormalizer.normalize("Ａ－Ｂ"));
    }

    @Test
    void normalize_shouldConvertFullWidthSpaceToNormalSpace() {
        // 全角空格（U+3000）转为普通空格后参与 trim 与折叠
        assertEquals("A B", SourceCodeNormalizer.normalize("A　B"));
        assertEquals("AB", SourceCodeNormalizer.normalize("　AB　"));
    }

    // ---- 规则 4：英文字母 uppercase ----

    @Test
    void normalize_shouldUpperCaseLetters() {
        assertEquals("ABC-DEF", SourceCodeNormalizer.normalize("abc-def"));
        // 非英文/非字母字符不受影响
        assertEquals("茶色123", SourceCodeNormalizer.normalize("茶色123"));
    }

    // ---- 规则 5：折叠连续空白 ----

    @Test
    void normalize_shouldCollapseConsecutiveWhitespace() {
        assertEquals("A B", SourceCodeNormalizer.normalize("A   B"));
        assertEquals("A B", SourceCodeNormalizer.normalize("A \t B"));
        // 单个内部空格保留，不做删除
        assertEquals("A B", SourceCodeNormalizer.normalize("A B"));
    }

    // ---- 规则 6：- 两侧空白移除 ----

    @Test
    void normalize_shouldRemoveWhitespaceAroundHyphen() {
        assertEquals("A-B", SourceCodeNormalizer.normalize("A - B"));
        assertEquals("A-B", SourceCodeNormalizer.normalize("A -B"));
        assertEquals("A-B", SourceCodeNormalizer.normalize("A- B"));
        assertEquals("A-B-C", SourceCodeNormalizer.normalize("A - B - C"));
    }

    // ---- 规则 7：保留 / . _ 等有业务语义的字符 ----

    @Test
    void normalize_shouldPreserveSemanticCharacters() {
        assertEquals("A/B.C_D", SourceCodeNormalizer.normalize("a/b.c_d"));
        // 这些字符两侧的空格不做特殊移除（仅折叠）
        assertEquals("A / B", SourceCodeNormalizer.normalize("A  /  B"));
    }

    // ---- 规则 8：不修改原始字段 ----

    @Test
    void normalize_shouldNotModifyInputAndKeepOriginalSemantics() {
        String raw = " mj-s96 ";
        String normalized = SourceCodeNormalizer.normalize(raw);
        assertEquals(" mj-s96 ", raw);
        assertEquals("MJ-S96", normalized);
    }

    // ---- null / 空白边界 ----

    @Test
    void normalize_shouldReturnNullForNullInput() {
        assertNull(SourceCodeNormalizer.normalize(null));
        assertNull(SourceCodeNormalizer.normalize(null, SourceCodeNormalizer.VERSION_1));
    }

    @Test
    void normalize_shouldReturnEmptyForBlankInput() {
        assertEquals("", SourceCodeNormalizer.normalize(""));
        assertEquals("", SourceCodeNormalizer.normalize("   "));
        assertEquals("", SourceCodeNormalizer.normalize("　"));
    }

    // ---- 规则 9：版本化 ----

    @Test
    void normalize_shouldDefaultToCurrentVersion() {
        assertEquals(SourceCodeNormalizer.normalize("mj-s96", SourceCodeNormalizer.CURRENT_VERSION),
            SourceCodeNormalizer.normalize("mj-s96"));
    }

    @Test
    void normalize_shouldSupportVersionOneExplicitly() {
        assertEquals("MJ-S96", SourceCodeNormalizer.normalize("mj-s96", SourceCodeNormalizer.VERSION_1));
    }

    @Test
    void normalize_shouldRejectUnsupportedVersion() {
        assertThrows(IllegalArgumentException.class,
            () -> SourceCodeNormalizer.normalize("mj-s96", 0));
        assertThrows(IllegalArgumentException.class,
            () -> SourceCodeNormalizer.normalize("mj-s96", 2));
        assertThrows(IllegalArgumentException.class,
            () -> SourceCodeNormalizer.normalize("mj-s96", -1));
    }

    @Test
    void versionMetadata_shouldExposeSupportedVersions() {
        assertTrue(SourceCodeNormalizer.isSupported(SourceCodeNormalizer.VERSION_1));
        assertEquals(List.of(SourceCodeNormalizer.VERSION_1), SourceCodeNormalizer.supportedVersions());
        assertEquals(SourceCodeNormalizer.VERSION_1, SourceCodeNormalizer.CURRENT_VERSION);
    }

    // ---- 组合：等价输入不受字符形态与空白位置影响 ----

    @Test
    void normalize_shouldBeDeterministicAcrossEquivalentInputs() {
        List<String> equivalents = List.of(
            " MJ-S96 ",
            "mj-s96",
            "ＭＪ－Ｓ９６",
            "MJ - S96",
            "  mj  -  s96  ",
            "ＭＪ － Ｓ９６"
        );
        for (String raw : equivalents) {
            assertEquals("MJ-S96", SourceCodeNormalizer.normalize(raw), "input: " + raw);
        }
    }
}
