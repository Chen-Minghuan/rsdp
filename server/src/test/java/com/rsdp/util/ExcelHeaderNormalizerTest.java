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

    @Test
    void disambiguateDuplicateHeaders_shouldSuffixDuplicates() {
        // P2-11：两个同名「价格」表头自动消歧，避免以表头为 key 时互相覆盖丢列
        Map<Integer, String> headers = new LinkedHashMap<>();
        headers.put(0, "型号");
        headers.put(1, "价格");
        headers.put(2, "价格");
        headers.put(3, "价格");
        headers.put(4, "");

        Map<Integer, String> result = ExcelHeaderNormalizer.disambiguateDuplicateHeaders(headers);

        assertEquals("型号", result.get(0));
        assertEquals("价格", result.get(1), "首个同名表头保持原名");
        assertEquals("价格#2", result.get(2));
        assertEquals("价格#3", result.get(3));
        assertEquals("", result.get(4), "空表头原样保留");
    }

    @Test
    void disambiguateDuplicateHeaders_shouldKeepUniqueHeadersUnchanged() {
        Map<Integer, String> headers = new LinkedHashMap<>();
        headers.put(0, "型号");
        headers.put(1, "名称");
        headers.put(2, "价格-A级布");

        Map<Integer, String> result = ExcelHeaderNormalizer.disambiguateDuplicateHeaders(headers);

        assertEquals(headers, result, "无重复表头时内容不变");
    }

    @Test
    void looksLikeEnglishMirrorRow_shouldDetectAsciiMirrorHeader() {
        // 沃高式英文对照副表头：SERIAL/PICTURE/SORT/ITEM NO. 整行 ASCII 且与中文表头列对齐
        Map<Integer, String> cnHeader = new LinkedHashMap<>();
        cnHeader.put(0, "序号");
        cnHeader.put(1, "图片");
        cnHeader.put(2, "类别");
        cnHeader.put(3, "型号");
        Map<Integer, String> enRow = new LinkedHashMap<>();
        enRow.put(0, "SERIAL");
        enRow.put(1, "PICTURE");
        enRow.put(2, "SORT");
        enRow.put(3, "ITEM NO.");

        org.junit.jupiter.api.Assertions.assertTrue(
            ExcelHeaderNormalizer.looksLikeEnglishMirrorRow(enRow, cnHeader),
            "英文对照行应判定为副表头行");
    }

    @Test
    void looksLikeEnglishMirrorRow_shouldNotMisjudgeChineseSubHeaderOrDataRow() {
        Map<Integer, String> cnHeader = new LinkedHashMap<>();
        cnHeader.put(0, "型号");
        cnHeader.put(1, "名称");
        cnHeader.put(2, "价格（PRICE）");
        cnHeader.put(3, "价格（PRICE）");

        // MUJU 式中文材质子表头：含中文，仍应走父子合并而非英文副表头
        Map<Integer, String> subHeader = new LinkedHashMap<>();
        subHeader.put(2, "A级布");
        subHeader.put(3, "半皮");
        org.junit.jupiter.api.Assertions.assertFalse(
            ExcelHeaderNormalizer.looksLikeEnglishMirrorRow(subHeader, cnHeader),
            "中文材质子表头不应判定为英文副表头");

        // 数据行：含中文品名/数字，不应误判
        Map<Integer, String> dataRow = new LinkedHashMap<>();
        dataRow.put(0, "ABC-001");
        dataRow.put(1, "休闲椅A");
        org.junit.jupiter.api.Assertions.assertFalse(
            ExcelHeaderNormalizer.looksLikeEnglishMirrorRow(dataRow, cnHeader),
            "数据行不应判定为英文副表头");

        // 与表头列不对齐的英文行（如散落的英文备注）不应误判
        Map<Integer, String> misaligned = new LinkedHashMap<>();
        misaligned.put(8, "NOTE");
        misaligned.put(9, "REMARK");
        org.junit.jupiter.api.Assertions.assertFalse(
            ExcelHeaderNormalizer.looksLikeEnglishMirrorRow(misaligned, cnHeader),
            "列位置不对齐的英文行不应判定为英文副表头");
    }

    @Test
    void countHeaderKeywordHits_shouldDistinguishHeaderFromTitle() {
        Map<Integer, String> header = new LinkedHashMap<>();
        header.put(0, "NO");
        header.put(1, "图片");
        header.put(2, "型号");
        header.put(3, "含税价");
        org.junit.jupiter.api.Assertions.assertTrue(
            ExcelHeaderNormalizer.countHeaderKeywordHits(header) >= 2, "真表头行关键词密度应 ≥2");

        Map<Integer, String> title = new LinkedHashMap<>();
        title.put(0, "曼柯家具有限公司");
        org.junit.jupiter.api.Assertions.assertTrue(
            ExcelHeaderNormalizer.countHeaderKeywordHits(title) < 2, "公司标题行关键词密度应 <2");
    }
}
