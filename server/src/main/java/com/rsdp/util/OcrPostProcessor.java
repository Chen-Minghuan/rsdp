package com.rsdp.util;

import com.rsdp.dto.Dimensions;
import com.rsdp.dto.OcrResult;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * OCR 结果后处理器。
 * 对 AI 返回的 OCR 结构化结果做清洗和规范化：
 * 1. 过滤无效型号（#、*、unknown 等）
 * 2. 过滤 slogan/标语类材质描述
 * 3. 从 dimensionText 中解析多组尺寸
 */
public class OcrPostProcessor {

    /**
     * 无效型号值集合。
     */
    private static final Set<String> INVALID_MODEL_NUMBERS = Set.of(
        "#", "*", "-", "—", "--", "///", "\\", "/", "无", "暂无", "unknown", "null", "", " ", "  "
    );

    /**
     * 常见 slogan/标语关键词。
     */
    private static final List<String> SLOGAN_KEYWORDS = List.of(
        "用真实木", "造好家具", "匠心", "品质生活", "专注", "传承", "极致",
        "每一刻", "让生活", "更美好", "追求", "因为专业", "所以卓越"
    );

    /**
     * 尺寸匹配正则：支持 2380*840*910、2380×840×910、2380 x 840 x 910 等写法。
     */
    private static final Pattern DIMENSION_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*[\\*×xX]\\s*(\\d+(?:\\.\\d+)?)(?:\\s*[\\*×xX]\\s*(\\d+(?:\\.\\d+)?))?"
    );

    /**
     * 单位匹配正则。
     */
    private static final Pattern UNIT_PATTERN = Pattern.compile(
        "(mm|cm|m|inch|英寸|厘米|米|毫米)",
        Pattern.CASE_INSENSITIVE
    );

    private OcrPostProcessor() {
        // 工具类禁止实例化
    }

    /**
     * 清洗整个 OCR 结果。
     *
     * @param ocr AI 返回的 OCR 结果
     */
    public static void clean(OcrResult ocr) {
        if (ocr == null) {
            return;
        }
        ocr.setModelNumber(cleanModelNumber(ocr.getModelNumber()));
        ocr.setMaterialDescription(cleanMaterialDescription(
            ocr.getMaterialDescription(), ocr.getBrand(), ocr.getFactoryName()
        ));
    }

    /**
     * 清洗型号：过滤无效符号和无意义值。
     *
     * @param modelNumber 原始型号
     * @return 清洗后的型号，无效时返回 null
     */
    public static String cleanModelNumber(String modelNumber) {
        if (!StringUtils.hasText(modelNumber)) {
            return null;
        }
        String trimmed = modelNumber.trim();
        if (INVALID_MODEL_NUMBERS.contains(trimmed)) {
            return null;
        }
        // 如果型号只是 1-2 个非字母数字字符（如 #、*），也视为无效
        if (trimmed.length() <= 2 && !trimmed.matches(".*[a-zA-Z0-9].*")) {
            return null;
        }
        return trimmed;
    }

    /**
     * 清洗材质描述：过滤 slogan、标语、品牌名、工厂名。
     *
     * @param materialDescription 原始材质描述
     * @param brand               品牌名
     * @param factoryName         工厂名
     * @return 清洗后的材质描述，无效时返回 null
     */
    public static String cleanMaterialDescription(String materialDescription, String brand, String factoryName) {
        if (!StringUtils.hasText(materialDescription)) {
            return null;
        }
        String trimmed = materialDescription.trim();

        // 与品牌名或工厂名相同，说明不是材质
        if (equalsIgnoreWhitespace(trimmed, brand) || equalsIgnoreWhitespace(trimmed, factoryName)) {
            return null;
        }

        // 包含 slogan 关键词
        for (String keyword : SLOGAN_KEYWORDS) {
            if (trimmed.contains(keyword)) {
                return null;
            }
        }

        // 过短且无材质关键词，认为无效
        if (trimmed.length() < 3) {
            return null;
        }

        return trimmed;
    }

    /**
     * 从 dimensionText 中解析多组尺寸。
     *
     * @param dimensionText 原始尺寸文字
     * @return 解析后的尺寸列表，解析失败返回空列表
     */
    public static List<Dimensions> parseDimensions(String dimensionText) {
        List<Dimensions> result = new ArrayList<>();
        if (!StringUtils.hasText(dimensionText)) {
            return result;
        }

        String globalUnit = parseUnit(dimensionText);

        // 按行分割，再按 / 、 ; 分割
        String[] lines = dimensionText.split("[\\r\\n]+");
        for (String line : lines) {
            // 去掉前缀说明，如"踏："、"尺寸："、"规格："等
            String cleanedLine = line.replaceAll("^[^\\d]*(?=\\d)", "").trim();
            if (!StringUtils.hasText(cleanedLine)) {
                continue;
            }

            // 一行内可能包含多组规格，用 / 、 ; 分割
            String[] segments = cleanedLine.split("[\\/、;；]");
            for (String segment : segments) {
                Dimensions dimensions = parseSingleDimension(segment, globalUnit);
                if (dimensions != null && isValidDimension(dimensions)) {
                    result.add(dimensions);
                }
            }
        }

        return result;
    }

    /**
     * 解析单个尺寸段。
     */
    private static Dimensions parseSingleDimension(String segment, String defaultUnit) {
        Matcher matcher = DIMENSION_PATTERN.matcher(segment);
        if (!matcher.find()) {
            return null;
        }

        Dimensions dimensions = new Dimensions();
        dimensions.setW(parseIntValue(matcher.group(1)));
        dimensions.setD(parseIntValue(matcher.group(2)));
        dimensions.setH(parseIntValue(matcher.group(3)));

        String unit = parseUnit(segment);
        dimensions.setUnit(unit != null ? unit : defaultUnit);

        return dimensions;
    }

    /**
     * 判断尺寸是否有效：至少有一个数值且单位非空。
     */
    private static boolean isValidDimension(Dimensions dimensions) {
        if (dimensions == null || !StringUtils.hasText(dimensions.getUnit())) {
            return false;
        }
        return dimensions.getW() != null || dimensions.getD() != null || dimensions.getH() != null;
    }

    /**
     * 从文本中解析单位。
     */
    private static String parseUnit(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        Matcher matcher = UNIT_PATTERN.matcher(text);
        if (matcher.find()) {
            String unit = matcher.group(1).toLowerCase();
            return switch (unit) {
                case "厘米" -> "cm";
                case "米" -> "m";
                case "毫米" -> "mm";
                case "英寸" -> "inch";
                default -> unit;
            };
        }
        return "mm"; // 默认 mm
    }

    /**
     * 将浮点数字符串转为整数（四舍五入）。
     */
    private static Integer parseIntValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            double doubleValue = Double.parseDouble(value);
            return (int) Math.round(doubleValue);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 忽略空白字符比较两个字符串。
     */
    private static boolean equalsIgnoreWhitespace(String a, String b) {
        if (!StringUtils.hasText(a) || !StringUtils.hasText(b)) {
            return false;
        }
        return a.replaceAll("\\s+", "").equals(b.replaceAll("\\s+", ""));
    }

    /**
     * 风格名称 → 字典 code 映射（与 category_dict 中的 style 类型保持一致）。
     *
     * <p>仅用于 OCR 文本、批量导入等非 VisionService 场景的风格别名兜底。
     * VisionService 的提示词已强制 AI 从 category_dict 标准枚举中选择，
     * 因此此处只保留常见变体/缩写，不再接受数据库外风格。</p>
     */
    private static final Map<String, String> STYLE_NAME_TO_CODE = Map.ofEntries(
        Map.entry("现代包豪斯", "MB"),
        Map.entry("现代极简包豪斯", "MB"),
        Map.entry("意式极简", "IT"),
        Map.entry("意式轻奢", "IL"),
        Map.entry("意式极简轻奢", "IL"),
        Map.entry("法式复古南洋", "FN"),
        Map.entry("法式南洋", "FN"),
        Map.entry("侘寂风", "WJ"),
        Map.entry("新中式", "NC"),
        Map.entry("新中式宋式", "ZS"),
        Map.entry("宋式", "ZS"),
        Map.entry("工业风LOFT", "IO"),
        Map.entry("工业LOFT", "IO"),
        Map.entry("孟菲斯多巴胺", "MD"),
        Map.entry("混搭", "HH"),
        Map.entry("顶尖大牌搭配", "DL")
    );

    /**
     * 将风格名称转换为字典 code。
     *
     * @param styleName 风格中文名
     * @return 风格 code，找不到时返回 null
     */
    public static String toStyleCode(String styleName) {
        if (!StringUtils.hasText(styleName)) {
            return null;
        }
        String normalized = styleName.trim();
        return STYLE_NAME_TO_CODE.get(normalized);
    }

    /**
     * 解析材质列表。
     *
     * @param materialDescription OCR 材质描述
     * @return 材质名称列表
     */
    public static List<String> parseMaterials(String materialDescription) {
        return splitByDelimiter(materialDescription);
    }

    /**
     * 按常见分隔符分割字符串，并过滤无效值。
     */
    private static List<String> splitByDelimiter(String text) {
        List<String> result = new ArrayList<>();
        if (!StringUtils.hasText(text)) {
            return result;
        }

        String[] parts = text.split("[\\/、;；,，|+]");
        for (String part : parts) {
            String trimmed = part.trim();
            if (StringUtils.hasText(trimmed)
                && !"unknown".equalsIgnoreCase(trimmed)
                && !"null".equalsIgnoreCase(trimmed)) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
