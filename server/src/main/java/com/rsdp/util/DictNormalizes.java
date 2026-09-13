package com.rsdp.util;

import com.rsdp.entity.CategoryDict;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 字典码归一与校验的共享实现（4.3 批①：三条录入链路的逐类副本收敛）。
 *
 * <p>纪律：纯搬移不改行为。各链路的口径差异（未命中返回原值还是 null、
 * 校验是否区分大小写）以独立方法显式保留，调用方按既有语义选择：</p>
 * <ul>
 *   <li>{@link #normalize}：传统 Excel 导入 / Excel AI 导入口径（未命中返回 trim 后原值）</li>
 *   <li>{@link #normalizeOrNull}：手工/工厂录入 matchDictCode 口径（未命中返回 null）</li>
 *   <li>{@link #isValid}：传统 Excel 导入口径（区分大小写）</li>
 *   <li>{@link #isValidIgnoreCase}：Excel AI 导入口径（忽略大小写）</li>
 * </ul>
 * <p>别名（dict_alias）解析不在本类职责内，由调用方在归一前先走别名快照。</p>
 */
public final class DictNormalizes {

    private DictNormalizes() {
    }

    /**
     * 归一为字典码：字典码（忽略大小写）→ 字典名；未命中返回 trim 后原值（由上层校验决定是否报错）。
     *
     * @param input 原始输入
     * @param dicts 字典列表
     * @return 归一后的字典码或 trim 后原值；输入为空返回 null
     */
    public static String normalize(String input, List<CategoryDict> dicts) {
        String matched = normalizeOrNull(input, dicts);
        if (matched != null) {
            return matched;
        }
        return StringUtils.hasText(input) ? input.trim() : null;
    }

    /**
     * 归一为字典码：字典码（忽略大小写）→ 字典名；未命中返回 null。
     *
     * @param input 原始输入
     * @param dicts 字典列表
     * @return 归一后的字典码；未命中或输入为空返回 null
     */
    public static String normalizeOrNull(String input, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(input)) {
            return null;
        }
        String trimmed = input.trim();
        for (CategoryDict d : dicts) {
            if (trimmed.equalsIgnoreCase(d.getDictCode())) {
                return d.getDictCode();
            }
        }
        for (CategoryDict d : dicts) {
            if (StringUtils.hasText(d.getDictName()) && trimmed.equals(d.getDictName())) {
                return d.getDictCode();
            }
        }
        return null;
    }

    /**
     * 校验字典码存在（区分大小写）。
     *
     * @param code  字典码
     * @param dicts 字典列表
     * @return 是否存在
     */
    public static boolean isValid(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equals(d.getDictCode()));
    }

    /**
     * 校验字典码存在（忽略大小写）。
     *
     * @param code  字典码
     * @param dicts 字典列表
     * @return 是否存在
     */
    public static boolean isValidIgnoreCase(String code, List<CategoryDict> dicts) {
        if (!StringUtils.hasText(code)) {
            return false;
        }
        return dicts.stream().anyMatch(d -> code.equalsIgnoreCase(d.getDictCode()));
    }
}
