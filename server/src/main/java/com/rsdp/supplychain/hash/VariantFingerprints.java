package com.rsdp.supplychain.hash;


import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Variant 身份序列化协议（设计文档 §17.1 身份取值部分）。
 *
 * <p>当前消费方为 {@link SnapshotTargetKeys} 的 Issue Target variantKey 计算：</p>
 * <ul>
 *   <li>{@link #coalesce}：与 PostgreSQL {@code COALESCE(code,text,'')} 严格同口径
 *       （不 trim、不 uppercase），v1 legacy 身份取值；</li>
 *   <li>{@link IdentityValue}：v2+ 类型化取值协议
 *       {@code CODE:<code> / TEXT:<normalized-text> / MM:<w>x<d>x<h> / NUM:<decimal> / ABSENT}——
 *       「解析失败」「只有原文」「真实缺失」不得都序列化为 {@code null}。</li>
 * </ul>
 *
 * <p>本类只负责确定性序列化。原 v1/v2 指纹哈希（legacyV1Hash / v2FingerprintHash）
 * 随 variant_fingerprint 表一并移除（2026-09-24 评审决策：指纹表摘除，判重维持
 * uk_variant_attrs 唯一索引口径）；未来若重启 v2 治理，从 git 历史恢复。</p>
 */
public final class VariantFingerprints {

    /**
     * Legacy 身份协议版本号（Issue Target variantKey 的版本段）。
     */
    public static final int LEGACY_VERSION = 1;

    private VariantFingerprints() {
        // 工具类禁止实例化
    }

    /**
     * 与 PostgreSQL {@code COALESCE(code,text,'')} 严格同口径：不 trim、不 uppercase。
     *
     * @return coalesce 结果
     */
    public static String coalesce(String code, String text) {
        return code != null ? code : (text != null ? text : "");
    }

    // ---------------------------------------------------------------- v2

    /**
     * v2+ Identity 字段的类型化取值（§17.1 表格）。序列化形式：
     * {@code CODE:<code>} / {@code TEXT:<normalized-text>} / {@code MM:<w>x<d>x<h>} /
     * {@code NUM:<canonical-decimal>} / {@code ABSENT}。
     *
     * @param kind  类型 discriminator
     * @param value 序列化后的值（ABSENT 时为空串）
     */
    public record IdentityValue(Kind kind, String value) {

        public IdentityValue {
            Objects.requireNonNull(kind, "kind 不能为 null");
            Objects.requireNonNull(value, "value 不能为 null");
        }

        /**
         * 类型 discriminator。
         */
        public enum Kind {
            /** 经验证的平台标准码。 */
            CODE,
            /** 无标准码、有归一原文。 */
            TEXT,
            /** 尺寸成功结构化（统一 mm 和规范数字格式）。 */
            MM,
            /** 结构化数值 Identity（如 widthMm=1850）。 */
            NUM,
            /** 源数据真实缺失（raw 和 normalized 都无值时才允许）。 */
            ABSENT
        }

        /**
         * 经验证标准码。
         */
        public static IdentityValue code(String canonicalCode) {
            if (canonicalCode == null || canonicalCode.isEmpty()) {
                throw new IllegalArgumentException("CODE 值不能为空");
            }
            return new IdentityValue(Kind.CODE, canonicalCode);
        }

        /**
         * 归一原文（由对应字段 Normalizer 版本的确定性结果给出，本类不再加工）。
         */
        public static IdentityValue text(String normalizedText) {
            if (normalizedText == null || normalizedText.isEmpty()) {
                throw new IllegalArgumentException("TEXT 值不能为空；真实缺失请使用 absent()");
            }
            return new IdentityValue(Kind.TEXT, normalizedText);
        }

        /**
         * 结构化尺寸（mm），数字按 §47.1.1 规范十进制输出。
         */
        public static IdentityValue mm(BigDecimal widthMm, BigDecimal depthMm, BigDecimal heightMm) {
            Objects.requireNonNull(widthMm, "widthMm 不能为 null");
            Objects.requireNonNull(depthMm, "depthMm 不能为 null");
            Objects.requireNonNull(heightMm, "heightMm 不能为 null");
            return new IdentityValue(Kind.MM,
                CanonicalSerializer.canonicalDecimal(widthMm) + "x"
                    + CanonicalSerializer.canonicalDecimal(depthMm) + "x"
                    + CanonicalSerializer.canonicalDecimal(heightMm));
        }

        /**
         * 结构化数值 Identity 字段（规范十进制）。
         */
        public static IdentityValue num(BigDecimal value) {
            Objects.requireNonNull(value, "value 不能为 null");
            return new IdentityValue(Kind.NUM, CanonicalSerializer.canonicalDecimal(value));
        }

        /**
         * 真实缺失。
         */
        public static IdentityValue absent() {
            return new IdentityValue(Kind.ABSENT, "");
        }

        /**
         * 固定优先级「标准码 → 归一原文 → ABSENT」的便捷构造（§17.1）。
         *
         * @param validatedCode  经验证标准码（无则 null / 空）
         * @param normalizedText 归一原文（无则 null / 空）
         * @return 类型化取值
         */
        public static IdentityValue of(String validatedCode, String normalizedText) {
            if (validatedCode != null && !validatedCode.isEmpty()) {
                return code(validatedCode);
            }
            if (normalizedText != null && !normalizedText.isEmpty()) {
                return text(normalizedText);
            }
            return absent();
        }

        /**
         * 序列化为 {@code KIND:value} 或 {@code ABSENT}。
         *
         * @return discriminator 文本
         */
        public String serialize() {
            return kind == Kind.ABSENT ? "ABSENT" : kind.name() + ':' + value;
        }
    }
}
