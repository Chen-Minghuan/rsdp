package com.rsdp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ManualOverrideFields} 单元测试（v3.3 §34.1，P0-D）。
 */
class ManualOverrideFieldsTest {

    @Test
    void markActive_shouldCreateMarkWithMetadata() {
        String json = ManualOverrideFields.markActive(null, "/factoryPrice", "USER-001",
            ManualOverrideFields.SOURCE_PRODUCT_UI);
        assertThat(ManualOverrideFields.isActive(json, "/factoryPrice")).isTrue();
        ManualOverrideFields.Mark mark = ManualOverrideFields.getMark(json, "/factoryPrice");
        assertThat(mark.correctedBy()).isEqualTo("USER-001");
        assertThat(mark.source()).isEqualTo("product_ui");
        assertThat(mark.correctedAt()).isNotBlank();
        // 治理元数据不复制敏感业务值
        assertThat(json).doesNotContain("1500");
    }

    @Test
    void markActive_shouldPreserveOtherPointers() {
        String json = ManualOverrideFields.markActive(null, "/factoryPrice", "U1", "product_ui");
        json = ManualOverrideFields.markActive(json, "/productName", "U2", "product_ui");
        assertThat(ManualOverrideFields.isActive(json, "/factoryPrice")).isTrue();
        assertThat(ManualOverrideFields.isActive(json, "/productName")).isTrue();
        assertThat(ManualOverrideFields.getMark(json, "/factoryPrice").correctedBy()).isEqualTo("U1");
    }

    @Test
    void isActive_shouldBeFalseForMissingOrInactive() {
        assertThat(ManualOverrideFields.isActive(null, "/factoryPrice")).isFalse();
        assertThat(ManualOverrideFields.isActive("{}", "/factoryPrice")).isFalse();
        String json = ManualOverrideFields.markActive(null, "/factoryPrice", "U1", "product_ui");
        json = ManualOverrideFields.deactivate(json, "/factoryPrice", "U2");
        assertThat(ManualOverrideFields.isActive(json, "/factoryPrice")).isFalse();
        // 停用后标记仍保留（active=false），不是删除
        assertThat(ManualOverrideFields.getMark(json, "/factoryPrice")).isNotNull();
        assertThat(ManualOverrideFields.getMark(json, "/factoryPrice").active()).isFalse();
    }

    @Test
    void activePointers_shouldListSorted() {
        String json = ManualOverrideFields.markActive(null, "/b", "U", "s");
        json = ManualOverrideFields.markActive(json, "/a", "U", "s");
        json = ManualOverrideFields.markActive(json, "/c", "U", "s");
        json = ManualOverrideFields.deactivate(json, "/b", "U");
        assertThat(ManualOverrideFields.activePointers(json)).containsExactly("/a", "/c");
    }

    @Test
    void merge_shouldUnionDisjointMarks() {
        String target = ManualOverrideFields.markActive(null, "/factoryPrice", "U1", "product_ui");
        String source = ManualOverrideFields.markActive(null, "/productName", "U2", "product_ui");
        var result = ManualOverrideFields.merge(target, source);
        assertThat(result.hasConflicts()).isFalse();
        assertThat(ManualOverrideFields.isActive(result.mergedJson(), "/factoryPrice")).isTrue();
        assertThat(ManualOverrideFields.isActive(result.mergedJson(), "/productName")).isTrue();
    }

    @Test
    void merge_shouldReportActiveConflictWithoutSilentlyDropping() {
        // §34.1：同一字段的活跃标记冲突 → 人工治理（冲突清单），不得静默丢弃；target 侧原值保留
        String target = ManualOverrideFields.markActive(null, "/factoryPrice", "U1", "product_ui");
        String source = ManualOverrideFields.markActive(null, "/factoryPrice", "U2", "product_ui");
        var result = ManualOverrideFields.merge(target, source);
        assertThat(result.hasConflicts()).isTrue();
        assertThat(result.conflicts()).containsExactly("/factoryPrice");
        assertThat(ManualOverrideFields.getMark(result.mergedJson(), "/factoryPrice").correctedBy())
            .isEqualTo("U1");
    }

    @Test
    void merge_shouldPreferActiveSideWhenOtherDeactivated() {
        String target = ManualOverrideFields.markActive(null, "/factoryPrice", "U1", "product_ui");
        target = ManualOverrideFields.deactivate(target, "/factoryPrice", "U1");
        String source = ManualOverrideFields.markActive(null, "/factoryPrice", "U2", "product_ui");
        var result = ManualOverrideFields.merge(target, source);
        assertThat(result.hasConflicts()).isFalse();
        assertThat(ManualOverrideFields.getMark(result.mergedJson(), "/factoryPrice").correctedBy())
            .isEqualTo("U2");
    }

    @Test
    void canonical_shouldTreatNullBlankAndEmptyObjectAsEqual() {
        assertThat(ManualOverrideFields.canonical(null)).isEqualTo("{}");
        assertThat(ManualOverrideFields.canonical("")).isEqualTo("{}");
        assertThat(ManualOverrideFields.canonical("{}")).isEqualTo("{}");
    }

    @Test
    void markActive_shouldRejectInvalidPointer() {
        assertThatThrownBy(() -> ManualOverrideFields.markActive(null, "factoryPrice", "U", "s"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManualOverrideFields.markActive(null, "/", "U", "s"))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parse_shouldTolerateIllegalJson() {
        // 非法 JSON 按空标记处理（列有 jsonb_typeof CHECK 兜底，正常不会发生）
        assertThat(ManualOverrideFields.isActive("not-json", "/x")).isFalse();
        assertThat(ManualOverrideFields.canonical("not-json")).isEqualTo("{}");
    }
}
