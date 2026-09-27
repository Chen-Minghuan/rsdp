package com.rsdp.supplychain.rsku;

import com.rsdp.util.SupplierConfigurationNormalizer;

/**
 * Supplier Configuration Key 生成与解析（设计文档 v3.3 §18.2，P0-D）。
 *
 * <p>Key 生成优先级：</p>
 * <pre>
 * 工厂稳定配置/等级码 → CODE:&lt;normalized supplier code&gt;
 * 只有原始等级/报价列名 → TEXT:&lt;normalized supplier text&gt;
 * 确认不存在配置拆分 → BASE
 * </pre>
 *
 * <p>{@code BASE} 只允许用于该 Factory + Variant 确实没有多个供应配置的情形
 * （§18.2）；同一源行展开出多个等级报价时必须生成多个非 BASE Key。</p>
 *
 * <p>本类只做确定性 Key 生成/解析，不做 Identity 绑定决策——TEXT→CODE 命中原 RSKU、
 * 冲突治理由 {@code SupplierConfigurationResolver}（§18.3）负责。归一规则版本化见
 * {@link SupplierConfigurationNormalizer}。</p>
 */
public final class SupplierConfigurationKeys {

    /** CODE 前缀：工厂稳定配置/等级码。 */
    public static final String CODE_PREFIX = "CODE:";
    /** TEXT 前缀：原始等级/报价列名。 */
    public static final String TEXT_PREFIX = "TEXT:";
    /** 无配置拆分身份的保留 Key（§18.2）。 */
    public static final String BASE = "BASE";

    private SupplierConfigurationKeys() {
    }

    /**
     * Key 身份段类型。
     */
    public enum Kind {
        CODE, TEXT, BASE
    }

    /**
     * 按 §18.2 优先级生成 Supplier Configuration Key（当前活跃归一版本）。
     *
     * @param supplierGradeCode 供应商原始等级/配置码（可空）
     * @param supplierGradeText 供应商原始等级/配置原文（可空）
     * @return CODE:/TEXT:/BASE Key
     */
    public static String of(String supplierGradeCode, String supplierGradeText) {
        return of(supplierGradeCode, supplierGradeText, SupplierConfigurationNormalizer.CURRENT_VERSION);
    }

    /**
     * 按 §18.2 优先级与指定归一版本生成 Key。
     *
     * @param supplierGradeCode 供应商原始等级/配置码（可空）
     * @param supplierGradeText 供应商原始等级/配置原文（可空）
     * @param normalizerVersion 归一规则集版本
     * @return CODE:/TEXT:/BASE Key
     */
    public static String of(String supplierGradeCode, String supplierGradeText, int normalizerVersion) {
        String code = emptyToNull(
            SupplierConfigurationNormalizer.normalizeCode(supplierGradeCode, normalizerVersion));
        if (code != null) {
            return CODE_PREFIX + code;
        }
        String text = emptyToNull(
            SupplierConfigurationNormalizer.normalizeText(supplierGradeText, normalizerVersion));
        if (text != null) {
            return TEXT_PREFIX + text;
        }
        return BASE;
    }

    /**
     * 由已归一的码值构造 CODE Key（码值须已经过对应版本归一）。
     *
     * @param normalizedCode 归一后的码（非空）
     * @return CODE Key
     */
    public static String ofNormalizedCode(String normalizedCode) {
        if (normalizedCode == null || normalizedCode.isEmpty()) {
            throw new IllegalArgumentException("normalizedCode 不能为空");
        }
        return CODE_PREFIX + normalizedCode;
    }

    /**
     * 解析 Key 的身份段类型。
     *
     * @param key supplier_configuration_key（非空）
     * @return 身份段类型
     */
    public static Kind kindOf(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("supplier_configuration_key 不能为空");
        }
        if (BASE.equals(key)) {
            return Kind.BASE;
        }
        if (key.startsWith(CODE_PREFIX)) {
            return Kind.CODE;
        }
        if (key.startsWith(TEXT_PREFIX)) {
            return Kind.TEXT;
        }
        throw new IllegalArgumentException("无法识别的 supplier_configuration_key 形态: " + key);
    }

    /**
     * 取 Key 的身份段值（BASE 返回 null）。
     *
     * @param key supplier_configuration_key（非空）
     * @return 身份段值
     */
    public static String payloadOf(String key) {
        Kind kind = kindOf(key);
        if (kind == Kind.BASE) {
            return null;
        }
        return key.substring(kind == Kind.CODE ? CODE_PREFIX.length() : TEXT_PREFIX.length());
    }

    /**
     * 判断 Key 形态是否合法（BASE 或 CODE:/TEXT: 且身份段非空）。
     *
     * @param key 待校验 Key（可为 null）
     * @return 是否合法
     */
    public static boolean isWellFormed(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        if (BASE.equals(key)) {
            return true;
        }
        if (key.startsWith(CODE_PREFIX)) {
            return key.length() > CODE_PREFIX.length();
        }
        if (key.startsWith(TEXT_PREFIX)) {
            return key.length() > TEXT_PREFIX.length();
        }
        return false;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
