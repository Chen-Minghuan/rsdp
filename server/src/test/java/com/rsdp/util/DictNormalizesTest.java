package com.rsdp.util;

import com.rsdp.entity.CategoryDict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DictNormalizes} 单元测试。
 */
class DictNormalizesTest {

    private final List<CategoryDict> dicts = List.of(
        dict("MC", "中古风"),
        dict("IT", "意式"));

    private CategoryDict dict(String code, String name) {
        CategoryDict d = new CategoryDict();
        d.setDictCode(code);
        d.setDictName(name);
        return d;
    }

    @Test
    void normalize_shouldMatchCodeIgnoreCase() {
        assertEquals("MC", DictNormalizes.normalize("mc", dicts));
        assertEquals("MC", DictNormalizes.normalize(" MC ", dicts));
    }

    @Test
    void normalize_shouldMatchName() {
        assertEquals("MC", DictNormalizes.normalize("中古风", dicts));
    }

    @Test
    void normalize_shouldReturnTrimmedOriginalWhenMiss() {
        assertEquals("太空舱", DictNormalizes.normalize(" 太空舱 ", dicts));
        assertNull(DictNormalizes.normalize("  ", dicts));
        assertNull(DictNormalizes.normalize(null, dicts));
    }

    @Test
    void normalizeOrNull_shouldReturnNullWhenMiss() {
        assertEquals("IT", DictNormalizes.normalizeOrNull("意式", dicts));
        assertNull(DictNormalizes.normalizeOrNull("太空舱", dicts));
        assertNull(DictNormalizes.normalizeOrNull(null, dicts));
    }

    @Test
    void isValid_shouldBeCaseSensitive() {
        assertTrue(DictNormalizes.isValid("MC", dicts));
        assertFalse(DictNormalizes.isValid("mc", dicts));
        assertFalse(DictNormalizes.isValid(null, dicts));
    }

    @Test
    void isValidIgnoreCase_shouldMatchAnyCase() {
        assertTrue(DictNormalizes.isValidIgnoreCase("mc", dicts));
        assertFalse(DictNormalizes.isValidIgnoreCase("XX", dicts));
        assertFalse(DictNormalizes.isValidIgnoreCase(" ", dicts));
    }
}
