package com.rsdp.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 表头清洗与多行表头合并测试。
 */
class ExcelHeaderNormalizerTest {

    @Test
    void clean_shouldRemoveEnglishNoteAndParenthesis() {
        assertEquals("型号品名", ExcelHeaderNormalizer.clean("型号品名 ITEM NO/DESCRIPTION"));
        assertEquals("产品尺寸", ExcelHeaderNormalizer.clean("产品尺寸(厘米) SIZE（CM）"));
        assertEquals("价格", ExcelHeaderNormalizer.clean("价格（PRICE）"));
        assertEquals("材质说明", ExcelHeaderNormalizer.clean("材质说明 SIZE"));
        assertEquals("规格/模块", ExcelHeaderNormalizer.clean("规格/模块 Modular Components"));
    }

    @Test
    void clean_shouldKeepEnglishOnlyHeader() {
        assertEquals("PRICE", ExcelHeaderNormalizer.clean("PRICE"));
        assertEquals("ITEM NO", ExcelHeaderNormalizer.clean("ITEM NO"));
    }

    @Test
    void clean_shouldNormalizeWhitespace() {
        assertEquals("产品图样", ExcelHeaderNormalizer.clean("  产品图样  PICTURE  "));
    }

    @Test
    void mergeHeaderRows_shouldMergeTwoLevelHeaders() {
        Map<Integer, String> primary = new LinkedHashMap<>();
        primary.put(0, "价格");
        primary.put(1, "价格");
        primary.put(2, "交期");

        Map<Integer, String> secondary = new LinkedHashMap<>();
        secondary.put(0, "A级布");
        secondary.put(1, "AA级布");

        Map<Integer, String> merged = ExcelHeaderNormalizer.mergeHeaderRows(List.of(primary, secondary));

        assertEquals("价格-A级布", merged.get(0));
        assertEquals("价格-AA级布", merged.get(1));
        assertEquals("交期", merged.get(2));
    }

    @Test
    void mergeHeaderRows_shouldSkipInvalidSecondaryRow() {
        Map<Integer, String> primary = new LinkedHashMap<>();
        primary.put(0, "品类");
        primary.put(1, "名称");

        Map<Integer, String> secondary = new LinkedHashMap<>();
        secondary.put(0, "FS123456789012345678901234567890"); // 超过 30 字符，应视为数据行
        secondary.put(1, "休闲椅 A");

        Map<Integer, String> merged = ExcelHeaderNormalizer.mergeHeaderRows(List.of(primary, secondary));

        assertEquals("品类", merged.get(0));
        assertEquals("名称", merged.get(1));
    }

    @Test
    void looksLikeHeaderRow_shouldReturnFalseForDataRow() {
        Map<Integer, String> dataRow = new LinkedHashMap<>();
        dataRow.put(0, "FS");
        dataRow.put(1, "休闲椅 A");
        dataRow.put(2, "中古风");
        assertFalse(ExcelHeaderNormalizer.looksLikeHeaderRow(dataRow));
    }

    @Test
    void looksLikeHeaderRow_shouldReturnFalseForPriceDataRow() {
        Map<Integer, String> dataRow = new LinkedHashMap<>();
        dataRow.put(0, "A级布");
        dataRow.put(1, "1999.00");
        assertFalse(ExcelHeaderNormalizer.looksLikeHeaderRow(dataRow));
    }

    @Test
    void looksLikeHeaderRow_shouldReturnTrueForHeaderRow() {
        Map<Integer, String> headerRow = new LinkedHashMap<>();
        headerRow.put(0, "A级布");
        headerRow.put(1, "AA级布");
        headerRow.put(2, "S级布");
        assertTrue(ExcelHeaderNormalizer.looksLikeHeaderRow(headerRow));
    }

    @Test
    void mergeHeaderRows_shouldInferParentHeaderSpan() {
        // 模拟工厂报价单："价格（PRICE）"只写在第一列，下方多列是不同材质
        Map<Integer, String> primary = new LinkedHashMap<>();
        primary.put(0, "型号品名");
        primary.put(1, "产品尺寸");
        primary.put(2, "价格（PRICE）");
        primary.put(7, "交期");

        Map<Integer, String> secondary = new LinkedHashMap<>();
        secondary.put(2, "A级布");
        secondary.put(3, "AA级布");
        secondary.put(4, "S级布");
        secondary.put(5, "SS级进口布");
        secondary.put(6, "半皮");
        // 注意：子表头行不能含数字，否则会被判定为数据行

        Map<Integer, String> merged = ExcelHeaderNormalizer.mergeHeaderRows(List.of(primary, secondary));

        assertEquals("型号品名", merged.get(0));
        assertEquals("产品尺寸", merged.get(1));
        assertEquals("价格（PRICE）-A级布", merged.get(2));
        assertEquals("价格（PRICE）-AA级布", merged.get(3));
        assertEquals("价格（PRICE）-S级布", merged.get(4));
        assertEquals("价格（PRICE）-SS级进口布", merged.get(5));
        assertEquals("价格（PRICE）-半皮", merged.get(6));
        assertEquals("交期", merged.get(7));
    }
}
