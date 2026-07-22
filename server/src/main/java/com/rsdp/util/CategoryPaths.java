package com.rsdp.util;

/**
 * 品类编码与品类路径（JSON 数组字符串）的统一映射。
 *
 * <p>供产品录入、人工批量导入、AI 批量导入三处链路共用，
 * 避免逐字重复的硬编码 switch 漂移。</p>
 */
public final class CategoryPaths {

    private CategoryPaths() {
        // 工具类禁止实例化
    }

    /**
     * 根据品类编码解析品类路径（JSON 数组字符串）。
     *
     * @param categoryCode 品类编码（如 SF、TB、OF）
     * @return 品类路径 JSON 字符串；未知编码默认返回单椅路径
     */
    public static String resolve(String categoryCode) {
        return switch (categoryCode) {
            case "SF" -> "[\"家具\",\"沙发\"]";
            case "TB" -> "[\"家具\",\"茶几\"]";
            case "FC" -> "[\"家具\",\"柜类\"]";
            case "BS" -> "[\"家具\",\"吧椅\"]";
            case "DT" -> "[\"家具\",\"桌子\"]";
            case "CB" -> "[\"家具\",\"柜子\"]";
            case "BD" -> "[\"家具\",\"床\"]";
            case "OF" -> "[\"办公家具\"]";
            default -> "[\"家具\",\"座椅\",\"休闲椅\",\"单椅\"]";
        };
    }
}
