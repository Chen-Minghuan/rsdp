package com.rsdp.util;

import com.rsdp.entity.RspuVariant;
import org.springframework.util.StringUtils;

/**
 * 变体属性判重 key 的共享实现（4.3 批③）。
 *
 * <p>两种口径显式分离，纯搬移不改行为：</p>
 * <ul>
 *   <li>{@link #effectiveOf}：RspuVariantService 创建判重口径（trim，码优先无码取原文）</li>
 *   <li>{@link #attrKey}：uk_variant_attrs 唯一索引同口径（COALESCE(code, text, '') 三段拼接，
 *       不 trim，与 DB 索引严格一致；合并工具的变体映射使用）</li>
 * </ul>
 */
public final class VariantKeys {

    private VariantKeys() {
    }

    /**
     * 有效判重值：码优先，无码取原文，均无则为空串（trim）。
     *
     * @param code 字典码
     * @param text 工厂原文
     * @return 有效判重值
     */
    public static String effectiveOf(String code, String text) {
        if (StringUtils.hasText(code)) {
            return code.trim();
        }
        return StringUtils.hasText(text) ? text.trim() : "";
    }

    /**
     * uk_variant_attrs 同口径的变体属性 key：COALESCE(size,size_text,'') | COALESCE(color,color_text,'')
     * | COALESCE(material,material_text,'')（不 trim，与数据库唯一索引严格一致）。
     *
     * @param variant 变体
     * @return 属性 key
     */
    public static String attrKey(RspuVariant variant) {
        return attrKey(variant.getSizeCode(), variant.getSizeText(),
            variant.getColorCode(), variant.getColorText(),
            variant.getMaterialCode(), variant.getMaterialText());
    }

    /**
     * uk_variant_attrs 同口径的变体属性 key（显式分段入参）。
     *
     * @return 属性 key
     */
    public static String attrKey(String sizeCode, String sizeText, String colorCode, String colorText,
                                 String materialCode, String materialText) {
        return coalesce(sizeCode, sizeText) + "|"
            + coalesce(colorCode, colorText) + "|"
            + coalesce(materialCode, materialText);
    }

    private static String coalesce(String a, String b) {
        return a != null ? a : (b != null ? b : "");
    }
}
