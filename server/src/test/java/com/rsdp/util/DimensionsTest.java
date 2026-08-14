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

    // ---------- 比例尺换算（scale_calc 第②级，v3.0 §4.4 P1） ----------

    @Test
    void parseScaleConversion_directPxRatio_shouldResolveMmPerPixel() {
        // 直接像素比例：1px=25mm / 全角冒号 / 中文单位 / 反向写法 / 每像素写法
        assertThat(Dimensions.parseScaleConversion("1px=25mm", null, null).x()).isEqualTo(25.0);
        assertThat(Dimensions.parseScaleConversion("1px：25mm", null, null).x()).isEqualTo(25.0);
        assertThat(Dimensions.parseScaleConversion("1像素=2.5厘米", null, null).x()).isEqualTo(25.0);
        assertThat(Dimensions.parseScaleConversion("25mm=1px", null, null).x()).isEqualTo(25.0);
        assertThat(Dimensions.parseScaleConversion("25mm/px", null, null).x()).isEqualTo(25.0);
        // 多像素比例：2px 比 1mm → 0.5mm/px
        assertThat(Dimensions.parseScaleConversion("2px 比 1mm", null, null).x()).isEqualTo(0.5);
    }

    @Test
    void parseScaleConversion_sheetPhysicalSize_shouldRequireImagePixelSize() {
        // 整图物理尺寸：需配合原图像素宽/高；2000×1500 像素 ↔ 10000×7500mm → 5mm/px
        Dimensions.MmPerPixel conversion =
            Dimensions.parseScaleConversion("图幅 10000mm×7500mm", 2000, 1500);
        assertThat(conversion.x()).isEqualTo(5.0);
        assertThat(conversion.y()).isEqualTo(5.0);
        // 缺原图像素尺寸时无法确立换算关系
        assertThat(Dimensions.parseScaleConversion("图幅 10000mm×7500mm", null, null)).isNull();
        assertThat(Dimensions.parseScaleConversion("图幅 10000mm×7500mm", 2000, null)).isNull();
    }

    @Test
    void parseScaleConversion_plainDrawingScale_shouldReturnNull() {
        // 单纯图纸比例无法确定图上 1 单位对应多少像素，不得瞎算
        assertThat(Dimensions.parseScaleConversion("1:100", 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("1:50", 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("1 : 100", 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("1：100", 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("1比100", 2000, 1500)).isNull();
    }

    @Test
    void parseScaleConversion_blankOrGarbage_shouldReturnNull() {
        assertThat(Dimensions.parseScaleConversion(null, 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("", 2000, 1500)).isNull();
        assertThat(Dimensions.parseScaleConversion("比例尺不详", 2000, 1500)).isNull();
    }
}
