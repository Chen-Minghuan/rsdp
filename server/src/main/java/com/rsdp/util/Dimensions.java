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

    private Dimensions() {
        // 工具类禁止实例化
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
}
