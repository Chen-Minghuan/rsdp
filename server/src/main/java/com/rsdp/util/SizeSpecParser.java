package com.rsdp.util;

import com.rsdp.dto.Dimensions;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多尺寸文字解析器（Excel AI 导入链路）。
 *
 * <p>从尺寸/备注/说明文字中识别"明确写了多个规格"的写法，用于把一个产品行展开为多个尺寸变体。
 * 支持三类写法：</p>
 * <ol>
 *   <li>多组三维/二维尺寸：{@code 2380*840*910/2600*840*910}、{@code 1.8×0.8×0.75m；2.0×0.9×0.75m}</li>
 *   <li>单值枚举列表：{@code 1.8m/2.0m/2.2m}、{@code 1800mm/2000mm/2200mm}、{@code 1.8米、2.0米}</li>
 *   <li>档位枚举：{@code 大/中/小}、{@code 大号/中号/小号}（须带"尺寸/规格/大小"上下文词，防误拆"小牛皮"）</li>
 * </ol>
 *
 * <p>保守原则：只有解析出 ≥2 个规格才返回非空列表（调用方据此展开变体）；
 * 单尺寸或无法识别时返回空列表，调用方维持原有单变体行为，完全不受影响。</p>
 */
public final class SizeSpecParser {

    /**
     * 一个尺寸规格：sizeText 为原文片段（保留原始写法），dimensions 为结构化三维（可空）。
     */
    public record SizeSpec(String sizeText, Dimensions dimensions) {
    }

    /** 三维/二维尺寸：2380*840*910、1.8×0.8×0.75（与 OcrPostProcessor 同规则） */
    private static final Pattern WHD_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*[\\*×xX]\\s*(\\d+(?:\\.\\d+)?)(?:\\s*[\\*×xX]\\s*(\\d+(?:\\.\\d+)?))?"
    );

    /** 单位：mm/cm/m/inch/英寸/厘米/米/毫米 */
    private static final Pattern UNIT_PATTERN = Pattern.compile(
        "(mm|cm|m|inch|英寸|厘米|米|毫米)",
        Pattern.CASE_INSENSITIVE
    );

    /** 单值尺寸（整段匹配）：1.8m、1800mm、2米、90cm 等 */
    private static final Pattern SINGLE_SIZE_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(mm|cm|m|毫米|厘米|米)",
        Pattern.CASE_INSENSITIVE
    );

    /** 枚举分隔符 */
    private static final Pattern SEGMENT_SPLIT = Pattern.compile("[/、，,；;\\r\\n]+");

    /** 档位词（整段精确匹配才命中） */
    private static final Set<String> GRADE_WORDS = Set.of(
        "大", "中", "小", "特大", "加大", "大号", "中号", "小号", "特大号"
    );

    /** 档位枚举的上下文词：文字中须出现其一才启用档位解析 */
    private static final Pattern GRADE_CONTEXT_PATTERN = Pattern.compile("尺寸|规格|大小");

    /** 配件标注前缀（踏/脚踏/脚凳等）：其尺寸属于配套配件而非本品规格，解析时排除 */
    private static final Pattern ACCESSORY_LABEL_PATTERN = Pattern.compile("^(脚踏|脚凳|踏|凳)\\s*[:：]");

    private SizeSpecParser() {
        // 工具类禁止实例化
    }

    /**
     * 从若干段文字中解析多尺寸规格（按优先级：多组尺寸 > 单值枚举 > 档位枚举）。
     *
     * @param texts 尺寸文字、描述/备注等候选文本（可多个，自动合并去重）
     * @return 解析出 ≥2 个规格时返回规格列表（按出现顺序去重）；否则返回空列表
     */
    public static List<SizeSpec> parse(String... texts) {
        List<String> candidates = new ArrayList<>();
        if (texts != null) {
            for (String text : texts) {
                if (StringUtils.hasText(text)) {
                    candidates.add(text.trim());
                }
            }
        }
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 1. 多组三维/二维尺寸（保留原文片段做 sizeText，避免小数取整后重建文本撞名）
        List<SizeSpec> multi = new ArrayList<>();
        for (String text : candidates) {
            String globalUnit = parseUnit(text);
            for (String segment : SEGMENT_SPLIT.split(text)) {
                String rawSeg = segment.trim();
                // 配件标注段（如"踏：960*600*400"）不是本品规格，跳过
                if (ACCESSORY_LABEL_PATTERN.matcher(rawSeg).find()) {
                    continue;
                }
                String seg = stripLeadingNonDigit(rawSeg);
                Matcher matcher = WHD_PATTERN.matcher(seg);
                if (!matcher.find()) {
                    continue;
                }
                Dimensions dim = new Dimensions();
                dim.setW(roundValue(matcher.group(1)));
                dim.setD(roundValue(matcher.group(2)));
                dim.setH(roundValue(matcher.group(3)));
                String unit = parseUnit(seg);
                dim.setUnit(unit != null ? unit : globalUnit);
                multi.add(new SizeSpec(seg, dim));
            }
        }
        List<SizeSpec> distinctMulti = distinct(multi);
        if (distinctMulti.size() >= 2) {
            return distinctMulti;
        }

        // 2. 单值枚举列表（须每段都带单位，防止把"2380/2600"这类无单位数字串误拆）
        List<SizeSpec> singles = new ArrayList<>();
        for (String text : candidates) {
            for (String segment : SEGMENT_SPLIT.split(text)) {
                String seg = stripLeadingNonDigit(segment.trim());
                Matcher matcher = SINGLE_SIZE_PATTERN.matcher(seg);
                if (matcher.matches()) {
                    singles.add(new SizeSpec(seg, toDimensions(matcher)));
                }
            }
        }
        List<SizeSpec> distinctSingles = distinct(singles);
        if (distinctSingles.size() >= 2) {
            return distinctSingles;
        }

        // 3. 档位枚举（须有尺寸/规格/大小上下文，防"小牛皮"类误拆）
        List<SizeSpec> grades = new ArrayList<>();
        for (String text : candidates) {
            if (!GRADE_CONTEXT_PATTERN.matcher(text).find()) {
                continue;
            }
            for (String segment : SEGMENT_SPLIT.split(text)) {
                String seg = stripLeadingContext(segment.trim());
                if (GRADE_WORDS.contains(seg)) {
                    grades.add(new SizeSpec(seg, null));
                }
            }
        }
        List<SizeSpec> distinctGrades = distinct(grades);
        if (distinctGrades.size() >= 2) {
            return distinctGrades;
        }

        return List.of();
    }

    /** 去掉段落开头的非数字前缀（如"尺寸：1.8m"→"1.8m"、"备注：2380*840"→"2380*840"）。 */
    private static String stripLeadingNonDigit(String segment) {
        return segment.replaceAll("^[^\\d]*(?=\\d)", "").trim();
    }

    /** 去掉段落开头的上下文前缀（如"尺寸：大"→"大"）。 */
    private static String stripLeadingContext(String segment) {
        return segment.replaceAll("^(尺寸|规格|大小)[:：]?", "").trim();
    }

    /** 解析并归一化单位（厘米→cm、米→m、毫米→mm、英寸→inch）；无单位时返回 null。 */
    private static String parseUnit(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = UNIT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String unit = matcher.group(1).toLowerCase();
        return switch (unit) {
            case "厘米" -> "cm";
            case "米" -> "m";
            case "毫米" -> "mm";
            case "英寸" -> "inch";
            default -> unit;
        };
    }

    /** 浮点数字符串转整数（四舍五入），与 OcrPostProcessor 同规则。 */
    private static Integer roundValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return (int) Math.round(Double.parseDouble(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 单值尺寸转结构化（仅整数值可入 w；小数值（如 1.8m）只保留 sizeText，不做单位换算）。 */
    private static Dimensions toDimensions(Matcher matcher) {
        String value = matcher.group(1);
        if (value.contains(".")) {
            return null;
        }
        Dimensions dim = new Dimensions();
        try {
            dim.setW(Integer.parseInt(value));
        } catch (NumberFormatException e) {
            return null;
        }
        dim.setUnit(matcher.group(2).toLowerCase());
        return dim;
    }

    /** 按 sizeText 去重（保持出现顺序）。 */
    private static List<SizeSpec> distinct(List<SizeSpec> specs) {
        Set<String> seen = new LinkedHashSet<>();
        List<SizeSpec> result = new ArrayList<>();
        for (SizeSpec spec : specs) {
            if (seen.add(spec.sizeText())) {
                result.add(spec);
            }
        }
        return result;
    }
}
