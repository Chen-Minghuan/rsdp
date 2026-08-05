package com.rsdp.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SizeSpecParser} 单元测试。
 *
 * <p>核心契约：只有明确写出 ≥2 个规格的文字才返回非空列表；
 * 单尺寸、无尺寸、易混淆文本一律返回空列表（调用方维持单变体行为）。</p>
 */
class SizeSpecParserTest {

    // ---------- 多组三维/二维尺寸 ----------

    @Test
    void parsesMultipleWhdGroupsSeparatedBySlash() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("2380*840*910/2600*840*910");
        assertEquals(2, specs.size());
        assertEquals(2380, specs.get(0).dimensions().getW());
        assertEquals(910, specs.get(0).dimensions().getH());
        assertEquals(2600, specs.get(1).dimensions().getW());
        assertNotNull(specs.get(0).sizeText());
    }

    @Test
    void parsesMultipleWhdGroupsWithUnitAndSemicolon() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1.8×0.8×0.75m；2.0×0.9×0.75m");
        assertEquals(2, specs.size());
        assertEquals("m", specs.get(0).dimensions().getUnit());
    }

    @Test
    void parsesMultilineDimensions() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("尺寸：2380*840*910mm\n2600*840*910mm");
        assertEquals(2, specs.size());
    }

    // ---------- 单值枚举列表 ----------

    @Test
    void parsesSingleValueEnumWithMeter() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1.8m/2.0m/2.2m");
        assertEquals(3, specs.size());
        assertEquals("1.8m", specs.get(0).sizeText());
        // 小数值不做单位换算，仅保留 sizeText
        assertNull(specs.get(0).dimensions());
    }

    @Test
    void parsesSingleValueEnumWithMm() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1800/2000/2200mm");
        // 只有末段带单位，前段无单位不命中 → 保守不展开
        assertTrue(specs.isEmpty());
    }

    @Test
    void parsesSingleValueEnumEachWithMm() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1800mm/2000mm/2200mm");
        assertEquals(3, specs.size());
        assertEquals(1800, specs.get(0).dimensions().getW());
        assertEquals("mm", specs.get(0).dimensions().getUnit());
    }

    @Test
    void parsesSingleValueEnumWithChineseUnit() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1.8米、2.0米");
        assertEquals(2, specs.size());
        assertEquals("1.8米", specs.get(0).sizeText());
    }

    // ---------- 档位枚举 ----------

    @Test
    void parsesGradeEnumWithSizeContext() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("尺寸：大/中/小");
        assertEquals(3, specs.size());
        assertEquals("大", specs.get(0).sizeText());
        assertNull(specs.get(0).dimensions());
    }

    @Test
    void parsesGradeEnumWithHaoSuffix() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("规格：大号/中号/小号");
        assertEquals(3, specs.size());
    }

    @Test
    void rejectsGradeEnumWithoutContext() {
        // 无"尺寸/规格/大小"上下文时不启用档位解析
        assertTrue(SizeSpecParser.parse("大/中/小").isEmpty());
    }

    // ---------- 负例：一律不展开 ----------

    @Test
    void rejectsSingleDimension() {
        assertTrue(SizeSpecParser.parse("2380*840*910mm").isEmpty());
    }

    @Test
    void rejectsMaterialWordsLikeXiaoPiNi() {
        assertTrue(SizeSpecParser.parse("小牛皮").isEmpty());
        assertTrue(SizeSpecParser.parse("材质：小牛皮/大荔枝纹").isEmpty());
    }

    @Test
    void rejectsBlankAndGarbage() {
        assertTrue(SizeSpecParser.parse((String) null).isEmpty());
        assertTrue(SizeSpecParser.parse("").isEmpty());
        assertTrue(SizeSpecParser.parse("详见附图").isEmpty());
        assertTrue(SizeSpecParser.parse().isEmpty());
    }

    @Test
    void rejectsUnitlessNumbers() {
        // 无单位数字串不拆，防误拆型号/货号
        assertTrue(SizeSpecParser.parse("2380/2600").isEmpty());
    }

    // ---------- 多文本合并与去重 ----------

    @Test
    void mergesMultipleTexts() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse(null, "备注：1.8m/2.0m");
        assertEquals(2, specs.size());
    }

    @Test
    void dedupesRepeatedSpecs() {
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse("1.8m/2.0m/1.8m");
        assertEquals(2, specs.size());
    }

    @Test
    void excludesAccessorySizeLikeFootstool() {
        // 实测案例：扶摇沙发规格文字含 3 组沙发尺寸 + 1 组脚踏尺寸，脚踏应排除
        List<SizeSpecParser.SizeSpec> specs = SizeSpecParser.parse(
            "2380*840*910/2600*840*910\n2800*840*910（分体）\n踏：960*600*400");
        assertEquals(3, specs.size());
        assertTrue(specs.stream().noneMatch(s -> s.sizeText().contains("960")));
    }
}
