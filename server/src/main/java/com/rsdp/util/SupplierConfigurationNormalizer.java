package com.rsdp.util;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;

/**
 * Supplier Configuration Normalizer（设计文档 v3.3 §18.2/§18.3，P0-D）。
 *
 * <p>供应商材质等级/报价包/供应配置进入 {@code rsku_supply.supplier_configuration_key}
 * 前的确定性归一工具。规则集显式版本化
 * （{@code rsku_supply.supplier_configuration_normalizer_version}）：</p>
 * <ul>
 *   <li>{@link #VERSION_1}：当前唯一已定义版本，见下方规则；</li>
 *   <li>未来新增版本以新增常量 + 分支方式并存；升级前的 Dry Run / 冲突治理
 *       （stable / one-to-one / converged / ambiguous 四分类）由
 *       {@code SupplierConfigurationMigrationService} 实现；</li>
 *   <li>{@link #CURRENT_VERSION} 指向当前活跃版本，新写入只允许使用该版本。</li>
 * </ul>
 *
 * <p>v1 规则（CODE 与 TEXT 共用基础归一，区别只在大小写折叠）：</p>
 * <ol>
 *   <li>Unicode NFKC；</li>
 *   <li>全角转半角（U+FF01–U+FF5E / U+3000 显式兜底）；</li>
 *   <li>trim；</li>
 *   <li>折叠连续空白为单个空格；</li>
 *   <li>CODE 额外做英文字母 uppercase（{@link Locale#ROOT}）——码值按 ASCII 大写口径判等；
 *       TEXT 不做大小写折叠（对齐 §17.1 v1 指纹的保守语义，原文差异即不同身份）。</li>
 * </ol>
 */
public final class SupplierConfigurationNormalizer {

    /** 规则集版本 1：当前唯一已定义版本。 */
    public static final int VERSION_1 = 1;

    /** 当前活跃规则集版本，新写入只能使用该版本。 */
    public static final int CURRENT_VERSION = VERSION_1;

    private static final char FULLWIDTH_START = '！';
    private static final char FULLWIDTH_END = '～';
    private static final int FULLWIDTH_OFFSET = 0xFEE0;
    private static final char FULLWIDTH_SPACE = '　';

    private SupplierConfigurationNormalizer() {
    }

    /**
     * 使用当前活跃版本归一供应商配置码（CODE 身份段）。
     *
     * @param raw 原始码（可为 null）
     * @return 归一结果；raw 为 null 时返回 null，raw 全为空白时返回空串
     */
    public static String normalizeCode(String raw) {
        return normalizeCode(raw, CURRENT_VERSION);
    }

    /**
     * 使用指定版本归一供应商配置码。
     *
     * @param raw     原始码（可为 null）
     * @param version 规则集版本
     * @return 归一结果；raw 为 null 时返回 null
     * @throws IllegalArgumentException 版本不受支持（不允许静默回落，避免 Identity 被错误归并）
     */
    public static String normalizeCode(String raw, int version) {
        if (version == VERSION_1) {
            String base = baseNormalizeV1(raw);
            return base == null ? null : base.toUpperCase(Locale.ROOT);
        }
        throw new IllegalArgumentException("Unsupported supplier_configuration_normalizer_version: " + version);
    }

    /**
     * 使用当前活跃版本归一供应商配置原文（TEXT 身份段，不折叠大小写）。
     *
     * @param raw 原始文本（可为 null）
     * @return 归一结果；raw 为 null 时返回 null
     */
    public static String normalizeText(String raw) {
        return normalizeText(raw, CURRENT_VERSION);
    }

    /**
     * 使用指定版本归一供应商配置原文。
     *
     * @param raw     原始文本（可为 null）
     * @param version 规则集版本
     * @return 归一结果；raw 为 null 时返回 null
     * @throws IllegalArgumentException 版本不受支持
     */
    public static String normalizeText(String raw, int version) {
        if (version == VERSION_1) {
            return baseNormalizeV1(raw);
        }
        throw new IllegalArgumentException("Unsupported supplier_configuration_normalizer_version: " + version);
    }

    /**
     * 判断指定规则集版本是否受支持。
     *
     * @param version 规则集版本
     * @return 是否受支持
     */
    public static boolean isSupported(int version) {
        return version == VERSION_1;
    }

    /**
     * 当前支持的全部规则集版本（升序）。升级 Dry Run 据此对新旧版本分别计算。
     *
     * @return 支持的版本列表
     */
    public static List<Integer> supportedVersions() {
        return List.of(VERSION_1);
    }

    /** v1 基础归一：NFKC → 全角转半角 → trim → 折叠连续空白。 */
    private static String baseNormalizeV1(String raw) {
        if (raw == null) {
            return null;
        }
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = toHalfWidth(s);
        s = s.trim();
        return s.replaceAll("\\s{2,}", " ");
    }

    /** 全角转半角：U+FF01–U+FF5E 平移到 ASCII，U+3000 转为普通空格；其余字符不动。 */
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
