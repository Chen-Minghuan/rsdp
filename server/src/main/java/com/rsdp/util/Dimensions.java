package com.rsdp.util;

import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 户型图尺寸标注解析公共工具。
 *
 * <p>从 {@code PublicAiMatchService.parseDimensionMm} 抽取（户型图空间搭配链路 v3.0 §4.4），
 * 管理端 {@code FloorPlanService} 与官网 {@code PublicAiMatchService} 双端共用，
 * 任何一端不允许私写解析规则副本。</p>
 */
public final class Dimensions {

    /** 毫米标注：4200×3800 / 4200*3800。 */
    private static final Pattern DIM_MM_PATTERN =
        Pattern.compile("(\\d{3,5})\\s*[×xX*]\\s*(\\d{3,5})");

    /** 米标注：4.2m*3.8m / 4.2×3.8m。 */
    private static final Pattern DIM_M_PATTERN =
        Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*m\\s*[×xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*m?");

    /** 长度单位（毫米/厘米/米，mm 须在 m 前避免前缀误配）。 */
    private static final String UNIT = "(毫米|厘米|米|mm|cm|m)";

    /** 像素↔长度直接比例（正向）：1px=25mm / 1像素：2.5厘米 / 2px 比 1mm。 */
    private static final Pattern PX_TO_LEN_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*(?:px|像素)\\s*[=:：比为]\\s*(\\d+(?:\\.\\d+)?)\\s*" + UNIT,
        Pattern.CASE_INSENSITIVE);

    /** 像素↔长度直接比例（反向）：25mm=1px / 2.5厘米：1像素。 */
    private static final Pattern LEN_TO_PX_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*" + UNIT + "\\s*[=:：比为]\\s*(\\d+(?:\\.\\d+)?)\\s*(?:px|像素)",
        Pattern.CASE_INSENSITIVE);

    /** 每像素长度：25mm/px / 2.5厘米/像素。 */
    private static final Pattern LEN_PER_PX_PATTERN = Pattern.compile(
        "(\\d+(?:\\.\\d+)?)\\s*" + UNIT + "\\s*/\\s*(?:px|像素)",
        Pattern.CASE_INSENSITIVE);

    /** 整图物理尺寸：图幅420mm×297mm / 图纸 84cm x 59.4cm（需配合原图像素宽/高换算）。 */
    private static final Pattern SHEET_SIZE_PATTERN = Pattern.compile(
        "(?:图幅|图纸|整张图|全图|图面)[^0-9]{0,6}"
            + "(\\d+(?:\\.\\d+)?)\\s*" + UNIT + "\\s*[×xX*]\\s*(\\d+(?:\\.\\d+)?)\\s*" + UNIT,
        Pattern.CASE_INSENSITIVE);

    private Dimensions() {
        // 工具类禁止实例化
    }

    /**
     * scale_calc 换算关系：每像素对应的毫米数（横向 x / 纵向 y）。
     *
     * @param x 横向每像素毫米数
     * @param y 纵向每像素毫米数
     */
    public record MmPerPixel(double x, double y) {
    }

    /**
     * 从尺寸标注原文解析开间/进深（mm）。
     *
     * <p>优先匹配毫米标注（如 "4200×3800"、"4200*3800"），
     * 其次匹配米标注（如 "4.2m*3.8m"、"4.2×3.8m"）并 ×1000 换算；
     * 无法解析时返回 null。</p>
     *
     * @param dimensionText 尺寸标注原文，可空
     * @return [widthMm, depthMm]，解析失败返回 null
     */
    public static int[] parseDimensionMm(String dimensionText) {
        if (!StringUtils.hasText(dimensionText)) {
            return null;
        }
        Matcher mmMatcher = DIM_MM_PATTERN.matcher(dimensionText);
        if (mmMatcher.find()) {
            return new int[] {Integer.parseInt(mmMatcher.group(1)), Integer.parseInt(mmMatcher.group(2))};
        }
        Matcher mMatcher = DIM_M_PATTERN.matcher(dimensionText);
        if (mMatcher.find()) {
            int widthMm = BigDecimal.valueOf(Double.parseDouble(mMatcher.group(1)))
                .multiply(BigDecimal.valueOf(1000)).intValue();
            int depthMm = BigDecimal.valueOf(Double.parseDouble(mMatcher.group(2)))
                .multiply(BigDecimal.valueOf(1000)).intValue();
            return new int[] {widthMm, depthMm};
        }
        return null;
    }

    /**
     * 解析比例尺标注为「像素↔毫米」换算关系（尺寸三级提取第②级 scale_calc，v3.0 §4.4）。
     *
     * <p>光栅图的比例尺换算必须有参照：仅当 scaleText 能解析出明确的像素↔毫米关系时才返回
     * 换算结果，支持两类写法（容忍空格与全角冒号）：</p>
     * <ul>
     *   <li>直接像素比例：{@code 1px=25mm} / {@code 1像素：2.5厘米} / {@code 25mm/px}
     *       （含反向 {@code 25mm=1px}）；</li>
     *   <li>整图物理尺寸：{@code 图幅420mm×297mm} / {@code 图纸 84cm x 59.4cm}，
     *       配合原图像素宽/高推算每像素毫米数（此时 imageWidthPx/imageHeightPx 必填）。</li>
     * </ul>
     *
     * <p>单纯的图纸比例（{@code 1:100} / {@code 1:50} / {@code 1比100}）无法确定图上 1 单位
     * 对应多少像素，一律返回 null（调用方落到第③级 AI 估算），不得瞎算。</p>
     *
     * @param scaleText     比例尺标注原文，可空
     * @param imageWidthPx  户型原图像素宽（仅整图物理尺寸写法需要），可空
     * @param imageHeightPx 户型原图像素高（仅整图物理尺寸写法需要），可空
     * @return 每像素毫米数（横向/纵向），解析不出可靠换算关系返回 null
     */
    public static MmPerPixel parseScaleConversion(String scaleText,
                                                  Integer imageWidthPx, Integer imageHeightPx) {
        if (!StringUtils.hasText(scaleText)) {
            return null;
        }
        // 直接像素比例（正向：N px = M <unit>）
        Matcher pxToLen = PX_TO_LEN_PATTERN.matcher(scaleText);
        if (pxToLen.find()) {
            double px = Double.parseDouble(pxToLen.group(1));
            double mm = Double.parseDouble(pxToLen.group(2)) * unitToMmFactor(pxToLen.group(3));
            if (px > 0 && mm > 0) {
                double mmPerPixel = mm / px;
                return new MmPerPixel(mmPerPixel, mmPerPixel);
            }
        }
        // 直接像素比例（反向：M <unit> = N px）
        Matcher lenToPx = LEN_TO_PX_PATTERN.matcher(scaleText);
        if (lenToPx.find()) {
            double mm = Double.parseDouble(lenToPx.group(1)) * unitToMmFactor(lenToPx.group(2));
            double px = Double.parseDouble(lenToPx.group(3));
            if (px > 0 && mm > 0) {
                double mmPerPixel = mm / px;
                return new MmPerPixel(mmPerPixel, mmPerPixel);
            }
        }
        // 每像素长度（M <unit>/px）
        Matcher lenPerPx = LEN_PER_PX_PATTERN.matcher(scaleText);
        if (lenPerPx.find()) {
            double mmPerPixel = Double.parseDouble(lenPerPx.group(1)) * unitToMmFactor(lenPerPx.group(2));
            if (mmPerPixel > 0) {
                return new MmPerPixel(mmPerPixel, mmPerPixel);
            }
        }
        // 整图物理尺寸（需原图像素宽/高才能确立换算关系）
        Matcher sheetSize = SHEET_SIZE_PATTERN.matcher(scaleText);
        if (sheetSize.find() && imageWidthPx != null && imageWidthPx > 0
            && imageHeightPx != null && imageHeightPx > 0) {
            double widthMm = Double.parseDouble(sheetSize.group(1)) * unitToMmFactor(sheetSize.group(2));
            double heightMm = Double.parseDouble(sheetSize.group(3)) * unitToMmFactor(sheetSize.group(4));
            if (widthMm > 0 && heightMm > 0) {
                return new MmPerPixel(widthMm / imageWidthPx, heightMm / imageHeightPx);
            }
        }
        return null;
    }

    /** 长度单位 → mm 换算系数（毫米/mm=1，厘米/cm=10，米/m=1000）。 */
    private static double unitToMmFactor(String unit) {
        return switch (unit.toLowerCase()) {
            case "毫米", "mm" -> 1.0;
            case "厘米", "cm" -> 10.0;
            case "米", "m" -> 1000.0;
            default -> 1.0;
        };
    }
}
