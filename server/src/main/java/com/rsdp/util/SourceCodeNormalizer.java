package com.rsdp.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Source Product Code Normalizer（设计文档 v3.3 §7）。
 *
 * <p>供应商（工厂）商品编码进入 {@code supplier_product_ref.normalized_source_product_code} 前的
 * 确定性归一工具。原始值 {@code source_product_code} 必须原样保存，本类不做任何原地修改。</p>
 *
 * <p>规则集显式版本化（{@code code_normalizer_version}）：</p>
 * <ul>
 *   <li>{@link #VERSION_1}：当前活跃规则集，见 {@link #normalizeV1(String)} 的逐条规则；</li>
 *   <li>未来新增版本以新增常量 + {@link #normalize(String, int)} 分支的方式并存，
 *       升级时的 Dry Run / 冲突治理流程（§7 末尾五步）由上层另行实现，本类只提供版本化归一入口；</li>
 *   <li>{@link #CURRENT_VERSION} 指向当前活跃版本，新写入只允许使用该版本。</li>
 * </ul>
 *
 * <p>v1 归一规则（与 §7 条目一一对应）：</p>
 * <ol>
 *   <li>trim；</li>
 *   <li>Unicode NFKC；</li>
 *   <li>全角转半角（NFKC 已覆盖大部分，此处对 U+FF01–U+FF5E / U+3000 做显式兜底）；</li>
 *   <li>英文字母 uppercase（{@link Locale#ROOT}）；</li>
 *   <li>折叠连续空白为单个空格；</li>
 *   <li>{@code -} 两侧空白移除；</li>
 *   <li>保留 {@code / . _} 等可能有业务语义的字符（不做任何删除/替换）；</li>
 *   <li>不修改原始字段（纯函数，调用方负责同时保存原文与归一值）。</li>
 * </ol>
 */
public final class SourceCodeNormalizer {

    /** 规则集版本 1：当前唯一已定义版本。 */
    public static final int VERSION_1 = 1;

    /** 当前活跃规则集版本，新写入只能使用该版本。 */
    public static final int CURRENT_VERSION = VERSION_1;

    /** 全角字符区起点（!）。 */
    private static final char FULLWIDTH_START = '！';
    /** 全角字符区终点（～）。 */
    private static final char FULLWIDTH_END = '～';
    /** 全角 ↔ 半角码位偏移。 */
    private static final int FULLWIDTH_OFFSET = 0xFEE0;
    /** 全角空格（表意空格）。 */
    private static final char FULLWIDTH_SPACE = '　';

    private SourceCodeNormalizer() {
    }

    /**
     * 使用当前活跃版本（{@link #CURRENT_VERSION}）归一供应商商品编码。
     *
     * @param raw 原始编码（可为 null）
     * @return 归一结果；raw 为 null 时返回 null，raw 全为空白时返回空串
     */
    public static String normalize(String raw) {
        return normalize(raw, CURRENT_VERSION);
    }

    /**
     * 使用指定版本规则集归一供应商商品编码。
     *
     * <p>用于读取历史版本数据与升级 Dry Run 时新旧版本并存计算；不支持的版本直接抛异常，
     * 不允许静默回落到其他版本（避免 Identity 被错误归并）。</p>
     *
     * @param raw 原始编码（可为 null）
     * @param version 规则集版本
     * @return 归一结果；raw 为 null 时返回 null
     * @throws IllegalArgumentException 版本不受支持
     */
    public static String normalize(String raw, int version) {
        if (version == VERSION_1) {
            return normalizeV1(raw);
        }
        throw new IllegalArgumentException("Unsupported code_normalizer_version: " + version);
    }

    /**
     * 判断指定规则集版本是否受支持（可用于校验历史数据中的 code_normalizer_version）。
     *
     * @param version 规则集版本
     * @return 是否受支持
     */
    public static boolean isSupported(int version) {
        return version == VERSION_1;
    }

    /**
     * 当前支持的全部规则集版本（升序）。升级 Dry Run 可据此对新旧版本分别计算归一值。
     *
     * @return 支持的版本列表
     */
    public static List<Integer> supportedVersions() {
        return List.of(VERSION_1);
    }

    /**
     * v1 规则集：trim → NFKC → 全角转半角 → uppercase → 折叠连续空白 → 移除 {@code -} 两侧空白。
     */
    private static String normalizeV1(String raw) {
        if (raw == null) {
            return null;
        }
        // 规则 2：Unicode NFKC（同时覆盖大部分全角→半角、兼容字符归一）
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        // 规则 3：全角转半角（显式兜底，保证规则独立于 JDK NFKC 实现细节）
        s = toHalfWidth(s);
        // 规则 1：trim（NFKC 后全角空格已转为普通空格，一并去掉）
        s = s.trim();
        // 规则 4：英文字母 uppercase
        s = s.toUpperCase(Locale.ROOT);
        // 规则 5：折叠连续空白为单个空格
        s = s.replaceAll("\\s{2,}", " ");
        // 规则 6：- 两侧空白移除
        s = s.replaceAll("\\s*-\\s*", "-");
        // 规则 7：/ . _ 等字符不做任何处理，原样保留
        return s;
    }

    /**
     * 全角转半角：U+FF01–U+FF5E 平移到 ASCII，U+3000 转为普通空格；其余字符不动。
     */
    private static String toHalfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == FULLWIDTH_SPACE) {
                sb.append(' ');
            } else if (c >= FULLWIDTH_START && c <= FULLWIDTH_END) {
                sb.append((char) (c - FULLWIDTH_OFFSET));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
