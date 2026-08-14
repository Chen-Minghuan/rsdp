package com.rsdp.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Dimensions} 单元测试（户型图尺寸标注解析公共方法，双端共用的回归保障）。
 */
class DimensionsTest {

    @Test
    void parseDimensionMm_mmNotation_shouldParseAsMm() {
        assertThat(Dimensions.parseDimensionMm("4200×3800"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("4200*3800"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("4200x3800"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("4200X3800"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("客厅 4200×3800"))
            .containsExactly(4200, 3800);
    }

    @Test
    void parseDimensionMm_meterNotation_shouldConvertToMm() {
        assertThat(Dimensions.parseDimensionMm("4.2m*3.8m"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("4.2m×3.8"))
            .containsExactly(4200, 3800);
        assertThat(Dimensions.parseDimensionMm("4.2m*3.8M"))
            .containsExactly(4200, 3800);
    }

    @Test
    void parseDimensionMm_noDimension_shouldReturnNull() {
        assertThat(Dimensions.parseDimensionMm(null)).isNull();
        assertThat(Dimensions.parseDimensionMm("")).isNull();
        assertThat(Dimensions.parseDimensionMm("客厅")).isNull();
        assertThat(Dimensions.parseDimensionMm("4200")).isNull();
    }

    @Test
    void parseDimensionMm_malformedText_shouldNotThrow() {
        // 畸形文本：缺第二维、仅单位、空白，均返回 null 且不抛异常
        assertThat(Dimensions.parseDimensionMm("4200×")).isNull();
        assertThat(Dimensions.parseDimensionMm("m×m")).isNull();
        assertThat(Dimensions.parseDimensionMm("4.2m×")).isNull();
        assertThat(Dimensions.parseDimensionMm("   ")).isNull();
    }
}
