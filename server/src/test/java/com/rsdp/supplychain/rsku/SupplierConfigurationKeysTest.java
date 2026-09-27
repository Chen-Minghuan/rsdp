package com.rsdp.supplychain.rsku;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SupplierConfigurationKeys} 单元测试（v3.3 §18.2，P0-D）。
 */
class SupplierConfigurationKeysTest {

    @Test
    void of_shouldPreferCodeOverText() {
        // §18.2 优先级：工厂稳定配置/等级码 → CODE:
        assertThat(SupplierConfigurationKeys.of("fabric_aa", "AA级布")).isEqualTo("CODE:FABRIC_AA");
    }

    @Test
    void of_shouldFallBackToTextWhenNoCode() {
        assertThat(SupplierConfigurationKeys.of(null, "AA级布")).isEqualTo("TEXT:AA级布");
        assertThat(SupplierConfigurationKeys.of("  ", "A级布")).isEqualTo("TEXT:A级布");
    }

    @Test
    void of_shouldReturnBaseWhenNoGradeEvidence() {
        assertThat(SupplierConfigurationKeys.of(null, null)).isEqualTo("BASE");
        assertThat(SupplierConfigurationKeys.of("", "  ")).isEqualTo("BASE");
    }

    @Test
    void of_shouldNormalizeCodeButKeepTextCase() {
        assertThat(SupplierConfigurationKeys.of("fabric-aa", null)).isEqualTo("CODE:FABRIC-AA");
        assertThat(SupplierConfigurationKeys.of(null, "Aa级布")).isEqualTo("TEXT:Aa级布");
    }

    @Test
    void kindOf_shouldClassify() {
        assertThat(SupplierConfigurationKeys.kindOf("BASE")).isEqualTo(SupplierConfigurationKeys.Kind.BASE);
        assertThat(SupplierConfigurationKeys.kindOf("CODE:FABRIC_AA"))
            .isEqualTo(SupplierConfigurationKeys.Kind.CODE);
        assertThat(SupplierConfigurationKeys.kindOf("TEXT:AA级布"))
            .isEqualTo(SupplierConfigurationKeys.Kind.TEXT);
        assertThatThrownBy(() -> SupplierConfigurationKeys.kindOf("WEIRD:x"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplierConfigurationKeys.kindOf(null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payloadOf_shouldExtractIdentityPayload() {
        assertThat(SupplierConfigurationKeys.payloadOf("CODE:FABRIC_AA")).isEqualTo("FABRIC_AA");
        assertThat(SupplierConfigurationKeys.payloadOf("TEXT:AA级布")).isEqualTo("AA级布");
        assertThat(SupplierConfigurationKeys.payloadOf("BASE")).isNull();
    }

    @Test
    void isWellFormed_shouldValidate() {
        assertThat(SupplierConfigurationKeys.isWellFormed("BASE")).isTrue();
        assertThat(SupplierConfigurationKeys.isWellFormed("CODE:X")).isTrue();
        assertThat(SupplierConfigurationKeys.isWellFormed("TEXT:x")).isTrue();
        assertThat(SupplierConfigurationKeys.isWellFormed("CODE:")).isFalse();
        assertThat(SupplierConfigurationKeys.isWellFormed("TEXT:")).isFalse();
        assertThat(SupplierConfigurationKeys.isWellFormed("")).isFalse();
        assertThat(SupplierConfigurationKeys.isWellFormed(null)).isFalse();
        assertThat(SupplierConfigurationKeys.isWellFormed("base")).isFalse();
    }

    @Test
    void ofNormalizedCode_shouldRejectEmpty() {
        assertThat(SupplierConfigurationKeys.ofNormalizedCode("FABRIC_AA")).isEqualTo("CODE:FABRIC_AA");
        assertThatThrownBy(() -> SupplierConfigurationKeys.ofNormalizedCode(""))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
