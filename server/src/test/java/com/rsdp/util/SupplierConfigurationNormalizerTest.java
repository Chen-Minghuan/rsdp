package com.rsdp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SupplierConfigurationNormalizer} 单元测试（v3.3 §18.2，P0-D）。
 */
class SupplierConfigurationNormalizerTest {

    @Test
    void normalizeCode_shouldTrimUppercaseAndFoldWhitespace() {
        assertThat(SupplierConfigurationNormalizer.normalizeCode("  fabric_aa ")).isEqualTo("FABRIC_AA");
        assertThat(SupplierConfigurationNormalizer.normalizeCode("aa 级   布")).isEqualTo("AA 级 布");
    }

    @Test
    void normalizeCode_shouldConvertFullWidthAndNfkc() {
        // 全角字母ＡＡ → 半角 AA 并 uppercase
        assertThat(SupplierConfigurationNormalizer.normalizeCode("ｆａｂｒｉｃ-ａａ")).isEqualTo("FABRIC-AA");
        // 全角空格 → 普通空格 → trim/折叠
        assertThat(SupplierConfigurationNormalizer.normalizeCode("AA　级布")).isEqualTo("AA 级布");
    }

    @Test
    void normalizeText_shouldNotFoldCase() {
        // TEXT 不做大小写折叠（保守身份语义）：Aa级布 与 AA级布 是不同身份
        assertThat(SupplierConfigurationNormalizer.normalizeText("Aa级布")).isEqualTo("Aa级布");
        assertThat(SupplierConfigurationNormalizer.normalizeText("AA级布")).isEqualTo("AA级布");
        // 但 trim / NFKC / 全角空格仍然生效
        assertThat(SupplierConfigurationNormalizer.normalizeText("  AA级布 ")).isEqualTo("AA级布");
        assertThat(SupplierConfigurationNormalizer.normalizeText("AA　级布")).isEqualTo("AA 级布");
    }

    @Test
    void normalize_shouldHandleNullAndBlank() {
        assertThat(SupplierConfigurationNormalizer.normalizeCode(null)).isNull();
        assertThat(SupplierConfigurationNormalizer.normalizeText(null)).isNull();
        assertThat(SupplierConfigurationNormalizer.normalizeCode("   ")).isEmpty();
        assertThat(SupplierConfigurationNormalizer.normalizeText("")).isEmpty();
    }

    @Test
    void normalize_shouldRejectUnsupportedVersion() {
        assertThatThrownBy(() -> SupplierConfigurationNormalizer.normalizeCode("X", 99))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unsupported supplier_configuration_normalizer_version");
        assertThatThrownBy(() -> SupplierConfigurationNormalizer.normalizeText("X", 0))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void versionMetadata_shouldExposeCurrentAndSupported() {
        assertThat(SupplierConfigurationNormalizer.CURRENT_VERSION)
            .isEqualTo(SupplierConfigurationNormalizer.VERSION_1);
        assertThat(SupplierConfigurationNormalizer.isSupported(1)).isTrue();
        assertThat(SupplierConfigurationNormalizer.isSupported(2)).isFalse();
        assertThat(SupplierConfigurationNormalizer.supportedVersions())
            .containsExactly(SupplierConfigurationNormalizer.VERSION_1);
    }
}
