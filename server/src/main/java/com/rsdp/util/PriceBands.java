package com.rsdp.util;

import java.math.BigDecimal;

/**
 * 出厂价价格带分档的共享实现（4.3 批⑤：RskuService / RskuImportService /
 * ExcelAiImportService 三处副本收敛）。
 *
 * <p>阈值口径（与三处既有实现一致，纯搬移不改行为）：
 * &lt; 1000 → low；1000 ≤ x &lt; 5000 → mid；≥ 5000 → high。
 * 注意是严格小于（历史注释曾误写 &lt;=，以代码为准）。</p>
 */
public final class PriceBands {

    /** low 档上限（不含） */
    public static final BigDecimal LOW_MAX = new BigDecimal("1000");
    /** mid 档上限（不含） */
    public static final BigDecimal MID_MAX = new BigDecimal("5000");

    public static final String LOW = "low";
    public static final String MID = "mid";
    public static final String HIGH = "high";

    private PriceBands() {
    }

    /**
     * 按阈值分档。
     *
     * @param price 出厂价（null 时返回 null，"unknown" 等兜底文案由调用方决定）
     * @return low / mid / high；price 为 null 返回 null
     */
    public static String of(BigDecimal price) {
        if (price == null) {
            return null;
        }
        if (price.compareTo(LOW_MAX) < 0) {
            return LOW;
        }
        if (price.compareTo(MID_MAX) < 0) {
            return MID;
        }
        return HIGH;
    }
}
