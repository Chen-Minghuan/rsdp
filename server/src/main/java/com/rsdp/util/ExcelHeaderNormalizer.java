package com.rsdp.util;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Excel 表头清洗与多行表头合并工具。
 *
 * <p>用于处理工厂报价单等复杂表头：中英双语、括号单位、多行父子表头。</p>
 */
public final class ExcelHeaderNormalizer {

    private ExcelHeaderNormalizer() {
    }

    // 匹配括号及其中内容（中文/英文括号）
    private static final Pattern PARENTHESIS_PATTERN = Pattern.compile("[（(][^）)]*[）)]");
    // 匹配纯英文/数字/符号片段（用于去除中英混合表头中的英文备注）
    private static final Pattern ASCII_FRAGMENT_PATTERN = Pattern.compile("[\\s]*[A-Za-z0-9/\\-_.\\s]+[\\s]*$");
    // 匹配连续空白
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    /**
     * 合并并清洗多行表头。
     *
     * <p>除纵向合并（同一列上下两行）外，还会横向推断父表头的覆盖范围，
     * 解决工厂报价单中常见的价格列合并单元格场景：第一行只有第一列写了"价格（PRICE）"，
     * 下方多列分别写"A级布"、"AA级布"等子表头。</p>
     *
     * @param headerRows 从 Excel 读取的原始表头行（通常 1~2 行）
     * @return 合并后的表头：key=列索引，value=原始合并表头（用于展示）
     */
    public static Map<Integer, String> mergeHeaderRows(List<Map<Integer, String>> headerRows) {
        if (headerRows == null || headerRows.isEmpty()) {
            return Map.of();
        }
        Map<Integer, String> primary = headerRows.get(0);
        if (headerRows.size() < 2) {
            return new LinkedHashMap<>(primary);
        }
        Map<Integer, String> secondary = headerRows.get(1);
        if (!looksLikeHeaderRow(secondary)) {
            return new LinkedHashMap<>(primary);
        }

        // 推断父表头的横向覆盖范围，处理合并单元格导致的父表头只出现在首列的问题
        Map<Integer, String> inferredParents = inferParentHeaderSpans(primary, secondary);

        Map<Integer, String> merged = new LinkedHashMap<>();
        int maxKey = inferredParents.keySet().stream().max(Integer::compareTo).orElse(0);
        maxKey = Math.max(maxKey, secondary.keySet().stream().max(Integer::compareTo).orElse(0));
        for (int i = 0; i <= maxKey; i++) {
            String parent = inferredParents.getOrDefault(i, "");
            String child = secondary.getOrDefault(i, "");
            String result = mergeTwoLevelHeader(parent, child);
            if (StringUtils.hasText(result)) {
                merged.put(i, result);
            }
        }
        return merged;
    }

    /**
     * 推断父表头的横向覆盖范围。
     *
     * <p>当父表头行中某个分组表头（如"价格"）只出现在连续子表头的第一列时，
     * 把它向右扩展到下一个非空父表头之前，使后续子表头能正确合并为"价格-A级布"、"价格-AA级布"等。</p>
     */
    private static Map<Integer, String> inferParentHeaderSpans(Map<Integer, String> primary,
                                                                 Map<Integer, String> secondary) {
        int maxKey = primary.keySet().stream().max(Integer::compareTo).orElse(0);
        maxKey = Math.max(maxKey, secondary.keySet().stream().max(Integer::compareTo).orElse(0));

        Map<Integer, String> result = new LinkedHashMap<>();
        for (int i = 0; i <= maxKey; i++) {
            result.put(i, primary.getOrDefault(i, ""));
        }

        for (int i = 0; i <= maxKey; i++) {
            String parent = result.getOrDefault(i, "");
            if (!StringUtils.hasText(parent) || !isGroupHeader(parent)) {
                continue;
            }
            // 向右扩展到下一个非空父表头之前
            for (int j = i + 1; j <= maxKey; j++) {
                String nextParent = primary.getOrDefault(j, "");
                if (StringUtils.hasText(nextParent)) {
                    break;
                }
                // 只有存在子表头的列才需要继承父表头
                if (StringUtils.hasText(secondary.getOrDefault(j, ""))) {
                    result.put(j, parent);
                }
            }
        }
        return result;
    }

    /**
     * 判断一个表头是否是分组父表头（需要向右扩展覆盖子表头）。
     */
    private static boolean isGroupHeader(String header) {
        if (!StringUtils.hasText(header)) {
            return false;
        }
        String h = header.toLowerCase();
        return h.contains("价格") || h.contains("price")
            || h.contains("出厂价") || h.contains("单价");
    }

    /**
     * 清洗单个表头文本：去除英文备注、括号单位、多余空格。
     *
     * <p>如果清洗后为空，则返回原始文本兜底。</p>
     */
    public static String clean(String header) {
        if (!StringUtils.hasText(header)) {
            return header;
        }
        String original = header.trim();
        String cleaned = original;

        // 1. 去除括号及内容
        cleaned = PARENTHESIS_PATTERN.matcher(cleaned).replaceAll("").trim();

        // 2. 去除末尾的英文/数字/符号备注（保留中文核心词）
        if (containsChinese(cleaned)) {
            cleaned = ASCII_FRAGMENT_PATTERN.matcher(cleaned).replaceAll("").trim();
        }

        // 3. 统一空白
        cleaned = WHITESPACE_PATTERN.matcher(cleaned).replaceAll(" ").trim();

        // 兜底：如果清洗后为空，返回原始值
        return StringUtils.hasText(cleaned) ? cleaned : original;
    }

    /**
     * 判断一行是否更像表头（尤其是多行表头中的子表头行）而非数据。
     *
     * <p>启发式规则：
     * - 非空单元格数量 >= 2
     * - 所有非空单元格都是短文本（<=15 字符）
     * - 所有非空单元格不包含阿拉伯数字
     * - 至少有一个单元格包含常见子表头关键词（布、皮、级、价格、规格等）</p>
     */
    public static boolean looksLikeHeaderRow(Map<Integer, String> row) {
        if (row == null || row.isEmpty()) {
            return false;
        }
        int nonEmptyCount = 0;
        boolean hasHeaderKeyword = false;
        for (String value : row.values()) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            nonEmptyCount++;
            String trimmed = value.trim();
            if (trimmed.length() > 15) {
                return false;
            }
            if (containsDigit(trimmed)) {
                return false;
            }
            if (containsHeaderKeyword(trimmed)) {
                hasHeaderKeyword = true;
            }
        }
        return nonEmptyCount >= 2 && hasHeaderKeyword;
    }

    private static String mergeTwoLevelHeader(String parent, String child) {
        String p = parent == null ? "" : parent.trim();
        String c = child == null ? "" : child.trim();
        if (!StringUtils.hasText(p) && !StringUtils.hasText(c)) {
            return "";
        }
        if (!StringUtils.hasText(p)) {
            return c;
        }
        if (!StringUtils.hasText(c) || p.equals(c)) {
            return p;
        }
        return p + "-" + c;
    }

    private static boolean containsChinese(String text) {
        if (text == null) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if (Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigit(String text) {
        if (text == null) {
            return false;
        }
        for (char c : text.toCharArray()) {
            if (Character.isDigit(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsHeaderKeyword(String text) {
        String h = text.toLowerCase();
        String[] keywords = {"布", "皮", "革", "级", "价格", "价", "规格", "尺寸", "材质", "颜色", "code", "no"};
        for (String kw : keywords) {
            if (h.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
